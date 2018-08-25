# Koyn Sync: Bitcoin Blockchain Headers Downloader

## Overview

koyn-sync is a java command line tool that downloads and verifies Bitcoin blockchain headers and save them locally to a binary file.

It supports syncing and verification resumption starting from the last downloaded header on subsequent runs. (This can be useful when updating previously synced headers with only the remaining ones.)

This tool can be used to preload Bitcoin blockchain headers to a microSD before using it with [Koyn Arduino Library](https://github.com/elkrem/koyn).

## Download

You can download the latest version directly from the repo's [releases page](https://github.com/elkrem/koyn-sync/releases).

## Usage

```
> java -jar koyn-sync-VERSION.jar

Initializing for Bitcoin testnet..
Reading local headers..
Verifying local headers: 100% [ 287945 / 287945 ] 0:00:06 / 0:00:00
Connecting to network..
Downloading and verifying headers: 100% [ 1326406 / 1326406 ] 0:02:59 / 0:00:00
All headers has been synced and verified successfully..

Press Enter key to exit..

```

Windows users can optionally run `koyn-syn-VERSION.exe` directly.

## Headers File Location

The download binary file can be found in either `./koyn/testnet/blkhdrs` or `./koyn/mainnet/blkhdrs`

## Optional Flags

```
  -h, --help           Show this help message.
  -n, --net            Which network to connect to, can be main or test. (defaults to: test)
  -o, --out            Override the default headers file location.(defaults to: ./koyn/net/blkhdrs)
  -p, --peers          Override the maximum networks peers to connect to. (defaults to: 30)
  -r, --resync         Ignore local headers, and resync all again from genesis.
```

## Building

To build the project and generate the jar file, run this command on the root of the repo:

```
.\gradlew assemble
```

The jar file can then be found in `./build/libs/`

## License and Copyright ##

```
This code is free software; you can redistribute it and/or modify it under the terms of the GNU General Public
License version 3 only, as published by the Free Software Foundation.

This code is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License version 3
for more details (a copy is included in the LICENSE.md file that accompanied this code).
```

