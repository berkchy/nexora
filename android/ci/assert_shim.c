/*
 * Minimal assert shim required by CS16Client's amxx build.
 * amxmodx uses __assert2/__assert_fail (glibc ABI). Android's bionic does not
 * provide them, so we wrap unresolved references onto this implementation.
 */
#include <stdio.h>
#include <stdlib.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Optional console hook. libamxmodx provides amxx_assert_report() (a tiny
 * C-callable wrapper around the server console) so the assertion also
 * reaches engine.log; without it the message would only exist in logcat,
 * where it is easy to miss. Declared weak so this file still links into
 * targets that do not provide it (e.g. libregex).
 */
extern void amxx_assert_report(const char *msg) __attribute__((weak));

static void assert_report(const char *msg) {
  fprintf(stderr, "%s", msg);
  if (amxx_assert_report)
    amxx_assert_report(msg);
}

__attribute__((noreturn)) void __wrap___assert2(const char *file, int line,
                                                const char *func,
                                                const char *failedexpr) {
  {
    char msg[512];
    snprintf(msg, sizeof(msg), "[ASSERT] %s:%d: %s: %s\n", file, line, func,
             failedexpr);
    assert_report(msg);
  }
  abort();
}

__attribute__((noreturn)) void __wrap___assert_fail(const char *assertion,
                                                    const char *file,
                                                    unsigned int line,
                                                    const char *function) {
  {
    char msg[512];
    snprintf(msg, sizeof(msg), "[ASSERT] %s:%d (%s): %s\n", file, line, function,
             assertion);
    assert_report(msg);
  }
  abort();
}

#ifdef __cplusplus
}
#endif