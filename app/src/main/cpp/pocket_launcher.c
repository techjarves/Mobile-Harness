#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 2) {
        fputs("Pocket launcher requires a program\n", stderr);
        return 64;
    }
    if (prctl(PR_SET_DUMPABLE, 1, 0, 0, 0) != 0) {
        perror("Pocket launcher could not enable child tracing");
        return 70;
    }
    unsetenv("LD_LIBRARY_PATH");
    unsetenv("LD_PRELOAD");
    execv(argv[1], &argv[1]);
    perror("Pocket launcher exec failed");
    return errno == 0 ? 71 : errno;
}
