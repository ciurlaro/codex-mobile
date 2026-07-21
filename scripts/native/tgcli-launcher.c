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
    const char *entry = getenv("CODEX_MOBILE_TGCLI_ENTRY");
    if (entry == NULL || entry[0] == '\0') {
        fputs("tgcli: bundled entry point is unavailable\n", stderr);
        return 127;
    }

    char directory[PATH_MAX];
    if (executable_directory(directory, sizeof(directory)) != 0) {
        perror("tgcli: resolve runtime directory");
        return 127;
    }

    char node[PATH_MAX];
    if (snprintf(node, sizeof(node), "%s/libcodex_node.so", directory) >= (int)sizeof(node)) {
        fputs("tgcli: runtime path is too long\n", stderr);
        return 127;
    }

    char **arguments = calloc((size_t)argc + 2, sizeof(char *));
    if (arguments == NULL) {
        perror("tgcli: allocate arguments");
        return 127;
    }
    arguments[0] = node;
    arguments[1] = (char *)entry;
    for (int index = 1; index < argc; index++) arguments[index + 1] = argv[index];

    execv(node, arguments);
    fprintf(stderr, "tgcli: unable to start Node: %s\n", strerror(errno));
    free(arguments);
    return 127;
}
