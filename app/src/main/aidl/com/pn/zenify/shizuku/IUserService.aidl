// IUserService.aidl
package com.pn.zenify.shizuku;

interface IUserService {
    // Reserved transaction id Shizuku uses to tear the service down.
    void destroy() = 16777114;

    void exit() = 1;

    // Run an arbitrary command (argv form) in the privileged process and
    // return combined stdout+stderr. Used for `am force-stop` etc.
    String execute(in String[] command) = 2;

    // Convenience: hibernate a single package. Returns "" on success or an
    // error string on failure.
    String forceStop(String packageName) = 3;
}
