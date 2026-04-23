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
func start_tunnel(token *C.char) C.int {
    goToken := C.GoString(token)
    os.Args = []string{"cloudflared", "tunnel", "run", "--token", goToken}

    // Note: This is a simplification. In reality, you need to modify cmd/cloudflared
    // to export the Execute function or create a wrapper.
    // For production, integrate with the actual cmd.Execute logic.

    // For now, assume we call the main logic
    // This requires modifying the cloudflared source to make Execute exportable.

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

## Notes

- This requires modifying cloudflared source code to expose the tunnel functionality as a library function.
- For log streaming, modify the Go code to accept a callback function that sends logs back to Java via JNI.
- Ensure all dependencies are statically linked for Android compatibility.