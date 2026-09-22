# cloudflared 安卓版补丁

> 日期：2026-09-23
> 上游版本：cloudflared `2026.9.1`（master 分支源码）

## 为什么要打这个补丁

在 Android 上运行官方预编译的 cloudflared，会**卡在找边缘服务器这一步**：

```
ERR edge discovery: error looking up Cloudflare edge IPs: the DNS query failed
error="lookup _v2-origintunneld._tcp.argotunnel.com on [::1]:53: read udp ... connection refused"
ERR Initiating shutdown error="Could not lookup srv records on _v2-origintunneld._tcp.argotunnel.com"
```

原因分两层：

1. **Go 的 SRV 查询只走纯 Go 解析器**，而纯 Go 解析器只认 `/etc/resolv.conf`。
   Android 上没有这个文件（DNS 由 netd 管理），所以默认路径必然失败。
   注意：即便用 `GOOS=android` 编译（这样 A/AAAA 记录能走系统解析器）也救不了 SRV，
   因为 cgo 的 `getaddrinfo` 压根不支持 SRV 记录。
2. **上游给的唯一退路是 DoT 到 `1.1.1.1:853`**，而 1.1.1.1 在不少网络环境下不可达
   （本机实测：`223.5.5.5:53` 21ms 通，`1.1.1.1:443` 直接超时）。
   退路断了，隧道就起不来。

于是现象是：**域名申请成功、界面上也能显示邀请链接，但从公网访问会拿到 Cloudflare 的 1033 错误页**。

## 补丁内容

文件：`edgediscovery/allregions/discovery.go`

在"退路"处，把单一的 DoT 改成**按顺序尝试多个解析器**：先保留原有的 DoT，不通再退到明文 UDP 的公共 DNS。

在第 34 行附近（`netLookupSRV` / `netLookupIP` 变量声明之后）插入：

```go
// DNS 服务器候选，按顺序逐个尝试。
//
// 为什么需要这张表（Android 补丁）：
// Go 的 SRV 查询**只走纯 Go 解析器**（cgo 的 getaddrinfo 不支持 SRV），而纯 Go 解析器
// 只认 /etc/resolv.conf —— Android 上没有这个文件，所以默认路径必然失败，
// 表现为 "lookup _v2-origintunneld._tcp.argotunnel.com on [::1]:53: connection refused"。
// 原实现唯一的退路是 DoT 到 1.1.1.1:853，而 1.1.1.1 在不少网络里根本不可达
// （本机实测：223.5.5.5:53 21ms 通、1.1.1.1:443 超时），于是隧道直接起不来。
// 这里在保留原 DoT 逻辑之后，补几条明文 UDP 的公共 DNS 作为后续退路。
var fallbackDNSServers = []struct {
	addr    string
	useTLS  bool
	tlsName string
}{
	{dotServerAddr, true, dotServerName}, // 原逻辑：Cloudflare 的 DNS over TLS
	{"223.5.5.5:53", false, ""},          // 阿里公共 DNS
	{"119.29.29.29:53", false, ""},       // DNSPod
	{"8.8.8.8:53", false, ""},            // Google
}
```

把 `lookupSRVWithDOT` 整个函数替换为：

```go
func lookupSRVWithDOT(srvService string, srvProto string, srvName string) (cname string, addrs []*net.SRV, err error) {
	// Inspiration: https://github.com/artyom/dot/blob/master/dot.go
	perTry := dotTimeout / time.Duration(len(fallbackDNSServers))
	for _, server := range fallbackDNSServers {
		network := "udp"
		if server.useTLS {
			network = "tcp"
		}
		r := &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, _ string, _ string) (net.Conn, error) {
				var dialer net.Dialer
				conn, err := dialer.DialContext(ctx, network, server.addr)
				if err != nil {
					return nil, err
				}
				if !server.useTLS {
					return conn, nil
				}
				return tls.Client(conn, &tls.Config{ServerName: server.tlsName}), nil
			},
		}
		ctx, cancel := context.WithTimeout(context.Background(), perTry)
		cname, addrs, err = r.LookupSRV(ctx, srvService, srvProto, srvName)
		cancel()
		if err == nil && len(addrs) > 0 {
			return cname, addrs, nil
		}
	}
	return cname, addrs, err
}
```

## 重新编译

源码放在英文路径（Go 工具链**不能**放在中文路径下，否则报 `package unsafe is not in std`）。
以下以 `G:\gotemp` 为例。

模拟器（x86_64）：

```powershell
$env:GOROOT = "G:\gotemp\go"
$env:GOPATH = "G:\gotemp\gopath"; $env:GOCACHE = "G:\gotemp\gocache"
$env:GOPROXY = "https://goproxy.cn,direct"; $env:GOTOOLCHAIN = "local"
$env:GOOS = "android"; $env:GOARCH = "amd64"; $env:CGO_ENABLED = "1"
$env:CC = "C:\Android\sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\x86_64-linux-android21-clang.cmd"
& "G:\gotemp\go\bin\go.exe" -C "G:\gotemp\cloudflared-master" build -o cloudflared-android-amd64 ./cmd/cloudflared
```

真机（arm64）：把 `GOARCH` 换成 `arm64`，`CC` 换成 `aarch64-linux-android21-clang.cmd`。

产物放到：

- `app/src/main/jniLibs/x86_64/libcloudflared.so`
- `app/src/main/jniLibs/arm64-v8a/libcloudflared.so`

## 打包为什么必须叫 lib*.so

Android 10 起禁止从可写目录执行文件，只有只读的 `nativeLibraryDir` 例外。
系统只把 `lib` 开头、`.so` 结尾的条目当 native 库处理并解压到那里、赋予执行权限。
所以 `app/build.gradle.kts` 里还必须开 `packaging { jniLibs { useLegacyPackaging = true } }`
—— 否则不会解压到磁盘，进程启动会报 "not found" 或 "Permission denied"。

编译时 Gradle 会提示 `Unable to strip ... libcloudflared.so`，这是正常的：它不是真的 .so，
剥不了符号表，按原样打包即可。
