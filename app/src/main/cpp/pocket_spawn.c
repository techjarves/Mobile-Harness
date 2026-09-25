#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void close_pair(int pair[2]) { close(pair[0]); close(pair[1]); }

JNIEXPORT jintArray JNICALL
Java_com_jarves_mh_runtime_NativeSpawn_spawn(JNIEnv *env, jobject self, jobjectArray java_argv,
                                               jobjectArray java_env, jstring java_cwd,
                                               jstring java_output, jboolean use_pty,
                                               jint pty_rows, jint pty_columns) {
    (void)self;
    jsize argc = (*env)->GetArrayLength(env, java_argv);
    jsize envc = (*env)->GetArrayLength(env, java_env);
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    char **envp = calloc((size_t)envc + 1, sizeof(char *));
    if (!argv || !envp) {
        // Partial-allocation leak guard: free whichever array did get created.
        free(argv);
        free(envp);
        return NULL;
    }
    for (jsize i = 0; i < argc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_argv, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        argv[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
    }
    for (jsize i = 0; i < envc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, java_env, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        envp[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
    }
    const char *cwd_utf = (*env)->GetStringUTFChars(env, java_cwd, NULL);
    char *cwd = strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, java_cwd, cwd_utf);
    const char *output_utf = (*env)->GetStringUTFChars(env, java_output, NULL);
    char *output_path = strdup(output_utf);
    (*env)->ReleaseStringUTFChars(env, java_output, output_utf);

    int in_pipe[2] = {-1, -1};
    int master_fd = -1;
    char *slave_name = NULL;
    if (use_pty) {
        master_fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
        if (master_fd < 0 || grantpt(master_fd) != 0 || unlockpt(master_fd) != 0) {
            if (master_fd >= 0) close(master_fd);
            return NULL;
        }
        const char *name = ptsname(master_fd);
        if (!name) { close(master_fd); return NULL; }
        slave_name = strdup(name);
        if (!slave_name) { close(master_fd); return NULL; }
    } else if (pipe(in_pipe) != 0) {
        return NULL;
    }
    pid_t pid = fork();
    if (pid == 0) {
        if (use_pty) {
            if (setsid() < 0) _exit(126);
            int slave_fd = open(slave_name, O_RDWR);
            if (slave_fd < 0 || ioctl(slave_fd, TIOCSCTTY, 0) != 0) _exit(126);
            struct winsize size = {
                .ws_row = (unsigned short)(pty_rows > 0 ? pty_rows : 40),
                .ws_col = (unsigned short)(pty_columns > 0 ? pty_columns : 120),
            };
            ioctl(slave_fd, TIOCSWINSZ, &size);
            struct termios terminal;
            if (tcgetattr(slave_fd, &terminal) == 0) {
                terminal.c_lflag &= (tcflag_t)~(ECHO | ECHONL);
                tcsetattr(slave_fd, TCSANOW, &terminal);
            }
            dup2(slave_fd, STDIN_FILENO);
            dup2(slave_fd, STDOUT_FILENO);
            dup2(slave_fd, STDERR_FILENO);
            if (slave_fd > STDERR_FILENO) close(slave_fd);
            close(master_fd);
        } else {
            // Give every runtime launch its own process group so stopping the wrapper
            // also stops Claude Code and commands spawned underneath it.
            setpgid(0, 0);
            close(in_pipe[1]);
            int output_fd = open(output_path, O_CREAT | O_TRUNC | O_WRONLY, 0600);
            if (output_fd < 0) _exit(126);
            dup2(in_pipe[0], STDIN_FILENO);
            dup2(output_fd, STDOUT_FILENO);
            dup2(output_fd, STDERR_FILENO);
            close(in_pipe[0]);
            close(output_fd);
        }
        if (chdir(cwd) != 0) _exit(126);
        prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
        execve(argv[0], argv, envp);
        // dprintf()/strerror() are not async-signal-safe after fork() from the
        // multi-threaded JVM and could deadlock the child on internal libc locks.
        // Report failure with a fixed write() and let the exit code speak.
        static const char exec_failed_msg[] = "native exec failed\n";
        (void)write(STDERR_FILENO, exec_failed_msg, sizeof(exec_failed_msg) - 1);
        _exit(127);
    }
    if (pid > 0 && !use_pty) setpgid(pid, pid);
    if (use_pty) free(slave_name);
    for (jsize i = 0; i < argc; i++) free(argv[i]);
    for (jsize i = 0; i < envc; i++) free(envp[i]);
    free(argv); free(envp); free(cwd); free(output_path);
    if (pid < 0) {
        if (use_pty) close(master_fd); else close_pair(in_pipe);
        return NULL;
    }
    if (!use_pty) close(in_pipe[0]);
    int input_fd = use_pty ? dup(master_fd) : in_pipe[1];
    if (input_fd < 0) {
        kill(pid, SIGKILL);
        if (use_pty) close(master_fd);
        return NULL;
    }
    jint values[3] = {pid, input_fd, use_pty ? master_fd : -1};
    jintArray result = (*env)->NewIntArray(env, 3);
    (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_jarves_mh_runtime_NativeSpawn_waitFor(JNIEnv *env, jobject self, jint pid, jboolean no_hang) {
    (void)env; (void)self;
    int status = 0;
    pid_t value = waitpid(pid, &status, no_hang ? WNOHANG : 0);
    if (value == 0) return -2;
    if (value < 0) return -128 - errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_jarves_mh_runtime_NativeSpawn_kill(JNIEnv *env, jobject self, jint pid, jint signal) {
    (void)env; (void)self;
    // Negative pid targets the whole runtime process group. Fall back to the
    // wrapper pid for devices where group creation raced with an early exit.
    int result = kill(-pid, signal);
    if (result != 0 && errno == ESRCH) result = kill(pid, signal);
    return result;
}
