#ifndef ELYSIUM_SPAWN_SHIM_H
#define ELYSIUM_SPAWN_SHIM_H

#include <sys/types.h>

int elysium_pty_spawn(
    const char *const argv[],
    const char *const envp[],
    const char *working_directory,
    unsigned short rows,
    unsigned short columns,
    int *master_fd_out,
    pid_t *pid_out,
    int *child_errno_out
);

#endif
