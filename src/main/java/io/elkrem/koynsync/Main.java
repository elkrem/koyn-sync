package io.elkrem.koynsync;

import com.google.common.io.Resources;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.bitcoinj.core.*;
import org.bitcoinj.core.listeners.NewBestBlockListener;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.utils.Threading;
import ru.creditnet.progressbar.ConsoleProgressBar;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.logging.LogManager;

import static java.util.concurrent.TimeUnit.SECONDS;

public class Main {
    private static NetworkParameters params;
    private static ArrayList<byte[]> list = new ArrayList<>();
    private static OptionSet options;
    private static File headersFile;
    private static FileLock lock;
    private static FileChannel channel;
    private static BlockStore store;
    private static BlockChain chain;
    private static PeerGroup peerGroup;
    private static BufferedOutputStream out;


    public static void main(String[] args) {
        String path;

        OptionParser parser = new OptionParser();
        parser.accepts("help");
        OptionSpec<String> netFlag = parser.accepts("net").withRequiredArg().ofType(String.class).defaultsTo("test");
        OptionSpec<String> outFlag = parser.accepts("out").withRequiredArg().ofType(String.class);
        parser.accepts("peers").withRequiredArg().ofType(Integer.class).defaultsTo(30);
        parser.accepts("resync");

        try {
            options = parser.parse(args);
        } catch (Exception ignored) {
            System.out.println("Incorrect arguments supplied, please check the docs and try again.");
            System.exit(1);
        }

        if (options.has("help")) {
            try {
                System.out.println(Resources.toString(Main.class.getResource("koyn-sync-help.txt"), StandardCharsets.UTF_8));
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        }

        String netDirectory;
        if (netFlag.value(options).toLowerCase().contains("main")) {
            System.out.println("Initializing for Bitcoin mainnet..");
            params = MainNetParams.get();
            netDirectory = "mainnet";

        } else if (netFlag.value(options).toLowerCase().contains("test")) {
            System.out.println("Initializing for Bitcoin testnet..");
            params = TestNet3Params.get();
            netDirectory = "testnet";
        } else {
            System.out.println("Incorrect flags provided, please see help!");
            return;
        }

        if (options.has("out")) {
            path = outFlag.value(options);
        } else {
            path = "koyn/" + netDirectory + "/blkhdrs";
        }

        Runtime.getRuntime().addShutdownHook(new Thread(Main::releaseResources));

        headersFile = new File(path);
        if (headersFile.exists()) {
            if (!options.has("resync")) {
                try {
                    System.out.println("Reading local headers..");
                    byte[] data = Files.readAllBytes(headersFile.toPath());
                    int effectiveFileSize = (data.length / 80) * 80;
                    for (int i = 0; i < effectiveFileSize; i += 80) {
                        byte[] header = Arrays.copyOfRange(data, i, i + 80);
                        list.add(header);
                    }
                } catch (IOException e) {
                    System.err.println("Failed to read local headers!");
                }
            } else {
                System.out.println("Ignoring local headers..");
            }
        } else {
            try {
                headersFile.getParentFile().mkdirs();
                headersFile.createNewFile();
            } catch (Exception e) {
                System.err.println("Couldn't create headers file, exiting!");
                System.exit(1);
            }
        }

        try {
            channel = new RandomAccessFile(headersFile, "rw").getChannel();
            lock = channel.tryLock();
        } catch (OverlappingFileLockException | IOException e) {
            System.err.println("Couldn't lock the headers file, exiting!");
            releaseResources();
            System.exit(1);
        }


        store = new MemoryBlockStore(params);
        MessageSerializer ser = new BitcoinSerializer(params, true);

        try {
            chain = new BlockChain(params, store);
        } catch (BlockStoreException e) {
            System.err.println("Couldn't initialize blockchain, exiting!");
            releaseResources();
            System.exit(1);
        }

        if (list.size() == 0) {
            list.add(params.getGenesisBlock().cloneAsHeader().bitcoinSerialize());
        } else {
            ConsoleProgressBar bar = new ConsoleProgressBar(list.size() - 1, "Verifying local headers:", System.out, 200);
            for (int i = 1; i < list.size(); i++) {
                Block block = ser.makeBlock(list.get(i));
                try {
                    chain.add(block);
                } catch (Exception e) {
                    bar.close();
                    System.err.println("Found some incorrect headers, and ignored them!");
                    list.subList(i, list.size()).clear();
                    break;
                }
                bar.step();
            }
            bar.close();
        }


        peerGroup = new PeerGroup(params, chain);
        peerGroup.setFastCatchupTimeSecs(new Date().getTime() / 1000);
        startPeerGroup(peerGroup);

        int blockToSync = peerGroup.getMostCommonChainHeight();

        out = new BufferedOutputStream(Channels.newOutputStream(channel), 100 * 80);

        for (byte[] aList : list) {
            try {
                out.write(aList);
            } catch (IOException e) {
                System.err.println("Couldn't write to local headers file, exiting!");
                releaseResources();
                System.exit(1);
            }
        }


        if (list.size() >= blockToSync) {
            System.out.println("Local headers are is up to date, no need to sync.");
            try {
                out.flush();
                out.close();
            } catch (IOException e) {
                System.err.println("Couldn't save local headers file, exiting!");
                releaseResources();
                System.exit(1);
            }
        } else {
            ConsoleProgressBar bar = new ConsoleProgressBar(blockToSync, list.size() - 1, "Downloading and verifying headers:", System.out, 200);
            BufferedOutputStream finalOut = out;
            NewBestBlockListener newBestBlockListener = block -> {

                byte[] header = block.getHeader().bitcoinSerialize();
                list.add(header);
                try {
                    finalOut.write(header);
                } catch (IOException e) {
                    bar.close();
                    System.err.println("Couldn't write to local headers file, exiting!");
                    releaseResources();
                    System.exit(1);
                }
                bar.stepTo(block.getHeight());

                if (block.getHeight() >= blockToSync) {
                    try {
                        bar.close();
                        finalOut.flush();
                        finalOut.close();
                        System.out.println("All headers has been synced and verified successfully..");
                    } catch (IOException e) {
                        System.err.println("Couldn't save local headers file, exiting!");
                        releaseResources();
                        System.exit(1);
                    }
                }
            };


            chain.addNewBestBlockListener(Threading.SAME_THREAD, newBestBlockListener);

            peerGroup.downloadBlockChain();

            peerGroup.stop();

            try {
                store.close();
            } catch (BlockStoreException e) {
                System.err.println("Couldn't release resources, exiting!");
                releaseResources();
                System.exit(1);
            }
        }

        releaseResources();

        try {
            System.out.println("\nPress Enter key to exit..");
            System.in.read();
        } catch (IOException ignored) {
        }

    }

    private static void startPeerGroup(PeerGroup peerGroup) {
        if (params.getDnsSeeds() != null) {
            peerGroup.setUserAgent("PeerMonitor", "1.0");
            peerGroup.setMaxConnections((Integer) options.valueOf("peers"));
            peerGroup.addPeerDiscovery(new DnsDiscovery(params));
            peerGroup.start();
            Future<List<Peer>> future = peerGroup.waitForPeers(4);
            System.out.println("Connecting to network..");
            try {
                future.get(60, SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                System.err.println("Couldn't connect to any peers, exiting!");
                releaseResources();
                System.exit(1);
            }
        }
    }

    private static void releaseResources() {
        try {
            if (peerGroup != null) peerGroup.stop();
            if (out != null) {
                out.flush();
                out.close();
            }
            if (store != null) store.close();
            if (lock != null) lock.release();
            if (channel != null) channel.close();
        } catch (Exception ignored) {
        }
    }
}






