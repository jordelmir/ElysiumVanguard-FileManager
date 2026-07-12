# Elysium Linux runtime third-party notice

The arm64 Linux runtime bundles separately executed native components from the
official Termux package repository. Elysium invokes PRoot as a child process;
it is not loaded into the application process through JNI.

## PRoot

- Package: `proot` 5.1.107.84 (`aarch64`)
- Binary package SHA-256: `59ace3b02894a9b87348eb5ccf246ed52ec64465021839422a151d7128acfe97`
- Source: https://github.com/termux/proot/tree/v5.1.107.84
- Source archive SHA-256: `a44ddbf18bc72c9780d56948b03aeda6d285392503ece0cae17cfc02e7bc7928`
- License: GPL-2.0-only (see `GPL-2.0-only.txt`)

The packaged executable's `libtalloc.so.2` dependency name is changed to
`libtalloc2.so` so Android's native-library packager can extract the dependency
with a valid `lib*.so` name. Program behavior and source code are otherwise
unchanged.

## talloc

- Package: `libtalloc` 2.4.3 (`aarch64`)
- Binary package SHA-256: `ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da`
- Source: https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz
- Source archive SHA-256: `dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd`
- Library source license: LGPL-3.0-or-later (see
  `LGPL-3.0-or-later.txt` and the incorporated GPLv3 terms in
  `GPL-3.0-only.txt`). The Termux package recipe labels the package
  `GPL-3.0`; the upstream `talloc.c` and `talloc.h` notices govern the
  library and state LGPL-3.0-or-later.

The packaged library's SONAME is correspondingly changed from
`libtalloc.so.2` to `libtalloc2.so`.

## libandroid-shmem

- Package: `libandroid-shmem` 0.7 (`aarch64`)
- Binary package SHA-256: `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6`
- Source: https://github.com/termux/libandroid-shmem/tree/v0.7
- Source archive SHA-256: `1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867`
- License: BSD-3-Clause (see `BSD-3-Clause.txt`)
- Copyright (c) 2013 Sergii Pylypenko; Copyright (c) 2017 Fredrik Fornwall.

Official package index: https://packages.termux.dev/apt/termux-main/
