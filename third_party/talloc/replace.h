#ifndef MOBILE_HARNESS_TALLOC_REPLACE_H
#define MOBILE_HARNESS_TALLOC_REPLACE_H

// Android/Bionic provides the portability functions used by talloc.c.
#include <errno.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_GETAUXVAL 1
#define HAVE_INTPTR_T 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_VA_COPY 1

#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif

#endif
