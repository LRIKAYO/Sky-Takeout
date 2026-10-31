# 苍穹外卖学习后端

当前覆盖 Day01～Day12，并补齐课程接口文档中的修改员工密码、用户退出。全部 72 项接口契约已有方法/路径，另有支付通知入口；使用 JDK 11、Spring Boot 2.7.3、MySQL、MyBatis、Redis、Spring Cache、JWT、Spring Task、WebSocket 和 Apache POI。

## 在当前工作区运行

```powershell
& 'D:\spring_poject\tools\start_project_local.ps1'
# 停止脚本记录且身份核对通过的本项目服务
& 'D:\spring_poject\tools\stop_project_local.ps1'
```

管理端：http://localhost/ ，接口文档：http://localhost:8080/doc.html 。小程序导入 `D:\spring_poject\weixin-app`，使用与后端一致的自己的 AppID。

启动脚本使用 Redis、当前代码的独立打包副本和课程 Nginx。本地启动默认关闭自动订单处理和演示任务；地图未配置时仅给本次进程关闭配送外呼，现有配置文件不改写。需要按课程自动取消超时单、完成派送单时，使用 `start_project_local.ps1 -EnableOrderTasks`，任务会更新学习库订单。

## 配置与移机

当前机器沿用已有 `.env.local` 和 `application-dev.yml`。在新机器复制 `.env.example` 为 `.env.local`，复制 `sky-server/src/main/resources/application-dev.example.yml` 为 `application-dev.yml`，填写自己的数据库、Redis、用户 JWT 密钥及微信配置；使用课程 SQL 创建专用新学习库。已有学习库继续沿用，不重新初始化。

现有脚本只在进程内加载本机变量，不输出凭据。手动启动时可使用 Spring 原生配置导入：

```powershell
mvn -B -DskipTests package
java -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar '--spring.config.import=optional:file:.env.local[.properties]' --sky.day10.order-task-enabled=false --sky.day10.demo-enabled=false
```

图片采用本地存储，保留 OSS 能力。真实地图需自己的店址、服务端 AK；真实微信支付需 `.env.payment.example` 列出的商户、密钥、证书和公网通知地址。预支付成功及本地测试夹具不代表真实付款，退款 PROCESSING 不代表到账。

## 补齐接口的行为

- `PUT /admin/employee/editPassword`：旧密码校验、课程 MD5 存储、本人身份由 JWT 决定。兼容原管理端不传 empId；主动传其他员工 ID 会拒绝。新密码为 6～20 位数字或字母；条件 UPDATE 防止同一旧密码并发修改均成功，记录修改时间和操作人。
- `POST /user/user/logout`：沿用课程员工退出的无状态 JWT 契约，成功后由客户端清除本地令牌。未引入令牌黑名单，退出不会立即撤销旧 JWT。

## 验收

```powershell
# 全部现有单元/集成测试及补齐接口检查：86 项
& 'D:\spring_poject\tools\verify_project_completion.ps1'
```

Day10/11/12 和补齐接口使用脚本拥有的空表临时库；旧测试沿用随机隔离夹具并清理，整轮核对原学习库行哈希。自动业务任务关闭，Redis 使用独立 16379 端口。日志位于 `D:\spring_poject\tools\project-completion-acceptance-regression.log`。

完整收尾、浏览器页面证据与外部接入边界见工作区的 `项目最终收尾与验收记录-20261028.md`。
