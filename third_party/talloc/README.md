# talloc source

This directory contains the library source files from the official talloc
2.4.3 release, downloaded from:

https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz

SHA-256: `dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd`

`replace.h` is an Android-specific portability shim. Android's Bionic libc
provides the standard functions used by `talloc.c`, so Samba's generated Waf
configuration and broader portability layer are unnecessary here.
