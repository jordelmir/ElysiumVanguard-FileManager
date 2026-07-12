#define _GNU_SOURCE

#include "spawn_shim.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void close_preserving_errno(int fd) {
    const int saved_errno = errno;
    if (fd >= 0) {
        (void)close(fd);
    }
    errno = saved_errno;
}

static int set_close_on_exec(int fd) {
    const int flags = fcntl(fd, F_GETFD);
    if (flags < 0) {
        return -1;
    }
    return fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
}

static void child_fail(int error_fd, int error_number) {
    const uint8_t *cursor = (const uint8_t *)&error_number;
    size_t remaining = sizeof(error_number);
    while (remaining > 0U) {
        const ssize_t written = write(error_fd, cursor, remaining);
        if (written > 0) {
            cursor += (size_t)written;
            remaining -= (size_t)written;
        } else if (written < 0 && errno == EINTR) {
            continue;
        } else {
            break;
        }
    }
    _exit(127);
}

int elysium_pty_spawn(
    const char *const argv[],
    const char *const envp[],
    const char *working_directory,
    unsigned short rows,
    unsigned short columns,
    int *master_fd_out,
    pid_t *pid_out,
    int *child_errno_out
) {
    if (argv == NULL || argv[0] == NULL || envp == NULL ||
        master_fd_out == NULL || pid_out == NULL || child_errno_out == NULL ||
        rows == 0U || columns == 0U) {
        errno = EINVAL;
        return -1;
    }

    *master_fd_out = -1;
    *pid_out = -1;
    *child_errno_out = 0;

    const int master_fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master_fd < 0) {
        return -1;
    }
    if (grantpt(master_fd) < 0 || unlockpt(master_fd) < 0) {
        close_preserving_errno(master_fd);
        return -1;
    }

    char slave_name[PATH_MAX];
    const int name_result = ptsname_r(master_fd, slave_name, sizeof(slave_name));
    if (name_result != 0) {
        errno = name_result;
        close_preserving_errno(master_fd);
        return -1;
    }

    int error_pipe[2] = {-1, -1};
    if (pipe(error_pipe) < 0) {
        close_preserving_errno(master_fd);
        return -1;
    }
    if (set_close_on_exec(error_pipe[0]) < 0 || set_close_on_exec(error_pipe[1]) < 0) {
        close_preserving_errno(error_pipe[0]);
        close_preserving_errno(error_pipe[1]);
        close_preserving_errno(master_fd);
        return -1;
    }

    const pid_t pid = fork();
    if (pid < 0) {
        close_preserving_errno(error_pipe[0]);
        close_preserving_errno(error_pipe[1]);
        close_preserving_errno(master_fd);
        return -1;
    }

    if (pid == 0) {
        (void)close(error_pipe[0]);
        (void)close(master_fd);

        if (setsid() < 0) {
            child_fail(error_pipe[1], errno);
        }

        const int slave_fd = open(slave_name, O_RDWR);
        if (slave_fd < 0) {
            child_fail(error_pipe[1], errno);
        }
        if (ioctl(slave_fd, TIOCSCTTY, 0) < 0) {
            child_fail(error_pipe[1], errno);
        }

        const struct winsize size = {
            .ws_row = rows,
            .ws_col = columns,
            .ws_xpixel = 0U,
            .ws_ypixel = 0U,
        };
        if (ioctl(slave_fd, TIOCSWINSZ, &size) < 0) {
            child_fail(error_pipe[1], errno);
        }

        if (dup2(slave_fd, STDIN_FILENO) < 0 ||
            dup2(slave_fd, STDOUT_FILENO) < 0 ||
            dup2(slave_fd, STDERR_FILENO) < 0) {
            child_fail(error_pipe[1], errno);
        }
        if (slave_fd > STDERR_FILENO) {
            (void)close(slave_fd);
        }

        if (working_directory != NULL && working_directory[0] != '\0' &&
            chdir(working_directory) < 0) {
            child_fail(error_pipe[1], errno);
        }

        execve(argv[0], (char *const *)argv, (char *const *)envp);
        child_fail(error_pipe[1], errno);
    }

    (void)close(error_pipe[1]);
    int child_error = 0;
    uint8_t *cursor = (uint8_t *)&child_error;
    size_t received = 0U;
    while (received < sizeof(child_error)) {
        const ssize_t count = read(error_pipe[0], cursor + received, sizeof(child_error) - received);
        if (count > 0) {
            received += (size_t)count;
        } else if (count == 0) {
            break;
        } else if (errno == EINTR) {
            continue;
        } else {
            const int read_error = errno;
            (void)kill(pid, SIGKILL);
            (void)waitpid(pid, NULL, 0);
            (void)close(error_pipe[0]);
            (void)close(master_fd);
            errno = read_error;
            return -1;
        }
    }
    (void)close(error_pipe[0]);

    if (received == sizeof(child_error)) {
        (void)waitpid(pid, NULL, 0);
        (void)close(master_fd);
        *child_errno_out = child_error;
        errno = child_error;
        return -1;
    }

    const int current_flags = fcntl(master_fd, F_GETFL);
    if (current_flags < 0 || fcntl(master_fd, F_SETFL, current_flags | O_NONBLOCK) < 0) {
        const int flag_error = errno;
        (void)kill(-pid, SIGKILL);
        (void)waitpid(pid, NULL, 0);
        (void)close(master_fd);
        errno = flag_error;
        return -1;
    }

    *master_fd_out = master_fd;
    *pid_out = pid;
    return 0;
}
