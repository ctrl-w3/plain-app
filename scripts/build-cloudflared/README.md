# Building cloudflared as Shared Library for Android

## Prerequisites

- Go 1.19+
- Android NDK
- Git

## Steps

1. Clone cloudflared source:
```bash
git clone https://github.com/cloudflare/cloudflared.git
cd cloudflared
```

2. Modify the source to export a function.

Create a new file `tunnel.go` in the root:

```go
package main

import (
    "C"
    "os"
    "github.com/cloudflare/cloudflared/cmd/cloudflared"
)

//export start_tunnel
func start_tunnel(token *C.char, logCallback unsafe.Pointer) C.int {
    goToken := C.GoString(token)
    os.Args = []string{"cloudflared", "tunnel", "run", "--token", goToken}

    // Set up logging callback
    // In the actual cloudflared code, redirect logs to the callback
    // For example, replace log.Printf with callback calls
    // The callback is a C function pointer that can be called from Go

    // This requires modifying the logging in cloudflared to use the callback

    return 0 // Success
}

    return 0 // Success
}

func main() {} // Empty main for c-shared build
```

3. Modify `cmd/cloudflared/main.go` to export the Execute function or create a wrapper.

For example, change `func main()` to `func Main()` and export it.

Then in tunnel.go:

```go
//export start_tunnel
func start_tunnel(token *C.char) C.int {
    goToken := C.GoString(token)
    os.Args = []string{"cloudflared", "tunnel", "run", "--token", goToken}
    cmd.Main() // Assuming Main is exported
    return 0
}
```

4. Build the shared library:

For arm64-v8a:
```bash
export CGO_ENABLED=1
export GOOS=android
export GOARCH=arm64
export CC=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang
export CXX=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang++

go build -buildmode=c-shared -o libcloudflared.so
```

5. Copy the generated files to Android project:
```bash
cp libcloudflared.so ../../../app/src/main/jniLibs/arm64-v8a/
cp libcloudflared.h ../../../app/src/main/cpp/
```

## Log Streaming

To enable log streaming to the Android UI:

1. Modify cloudflared's logging to accept a callback.

2. In the Go code, define the callback type:

```go
type LogCallback func(message *C.char)

var logCb LogCallback
```

3. Export a function to set the callback:

```go
//export set_log_callback
func set_log_callback(cb unsafe.Pointer) {
    logCb = (LogCallback)(cb)
}
```

4. In start_tunnel, call set_log_callback with the passed pointer.

5. Replace log.Printf calls with logCb(C.CString(message))

This will send logs back to the C code, which calls the Java callback.