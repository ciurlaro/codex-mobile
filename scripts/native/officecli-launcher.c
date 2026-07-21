#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int executable_directory(char *buffer, size_t size) {
    ssize_t length = readlink("/proc/self/exe", buffer, size - 1);
    if (length < 0 || (size_t)length >= size - 1) return -1;
    buffer[length] = '\0';
    char *slash = strrchr(buffer, '/');
    if (slash == NULL) return -1;
    *slash = '\0';
    return 0;
}

int main(int argc, char **argv) {
    const char *entry = getenv("CODEX_MOBILE_OFFICECLI_ENTRY");
    if (entry == NULL || entry[0] == '\0') {
        fputs("officecli: bundled entry point is unavailable\n", stderr);
        return 127;
    }

    char directory[PATH_MAX];
    if (executable_directory(directory, sizeof(directory)) != 0) {
        perror("officecli: resolve runtime directory");
        return 127;
    }

    char loader[PATH_MAX];
    if (snprintf(loader, sizeof(loader), "%s/libcodex_officecli_musl.so", directory) >=
        (int)sizeof(loader)) {
        fputs("officecli: runtime path is too long\n", stderr);
        return 127;
    }

    char **arguments = calloc((size_t)argc + 4, sizeof(char *));
    if (arguments == NULL) {
        perror("officecli: allocate arguments");
        return 127;
    }
    arguments[0] = loader;
    arguments[1] = "--library-path";
    arguments[2] = directory;
    arguments[3] = (char *)entry;
    for (int index = 1; index < argc; index++) arguments[index + 3] = argv[index];

    execv(loader, arguments);
    fprintf(stderr, "officecli: unable to start runtime: %s\n", strerror(errno));
    free(arguments);
    return 127;
}
