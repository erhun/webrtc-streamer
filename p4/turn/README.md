# TURN 部署说明

P2P 直连优先，P2P 失败（跨 NAT/对称型 NAT）时走 TURN 中继兜底。

## 部署

1. 安装 coturn：`apt install coturn`（或 `docker run -d coturn/coturn`）
2. 编辑 `turnserver.conf`，替换 `<YOUR_PUBLIC_IP>` 为公网 IP
3. 启动：`turnserver -c turnserver.conf`
4. 开放防火墙：UDP/TCP 3478（及 relay 端口段 49152-65535）

## 客户端 ICE 配置

Web 客户端和云机侧（scrcpy-server）的 `RTCConfiguration` 都要加：

```
iceServers: [
    { urls: 'stun:stun.example.com:3478' },
    { urls: 'turn:turn.example.com:3478', username: 'turnuser', credential: 'turnpassword' }
]
```

## 验证

`turnutils_uclient -u turnuser -w turnpassword turn.example.com` 返回 relay 地址即成功。
