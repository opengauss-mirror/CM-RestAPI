# CM-RestAPI

#### 介绍
{**以下是 Gitee 平台说明，您可以替换此简介**
Gitee 是 OSCHINA 推出的基于 Git 的代码托管平台（同时支持 SVN）。专为开发者提供稳定、高效、安全的云端软件开发协作平台
无论是个人、团队、或是企业，都能够用 Gitee 实现代码托管、项目管理、协作开发。企业项目请看 [https://gitee.com/enterprises](https://gitee.com/enterprises)}

#### 软件架构
软件架构说明


#### 编译教程

1.  下载CM-RestApi代码。

    ```
    git clone https://gitcode.com/opengauss/CM-RestAPI.git
    ```

2.  修改pom.xml文件，进行项目配置，并确保pom.xml里面配置与环境配置一致。

3.  通过脚本编译获取安装包，执行后会在./CM-RestAPI/target目录下生成jar包。

    ```
    cd ./CM-RestAPI
    sh build.sh
    ```

4.  如果某些参数中需要传入特殊字符，如“ < > 等，需要在application.properties中添加对应配置，然后再编译。参考如下：

    ```
    server.tomcat.relaxed-query-chars=\",<,>
    ```

#### 环境配置

以java-17、maven-3.9.9环境为例。

1.  安装maven-3.9.9、java-17。

2.  配置环境变量到当前用户~/.bashrc文件中。

    ```
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
    export PATH=$JAVA_HOME/bin:$PATH
    export MAVEN_HOME=/home/omm/pkg/apache-maven-3.9.9
    export PATH=$MAVEN_HOME/bin:$PATH
    ```

3.  确认是否设置成功。

    ```
    [omm@openGauss pkg]$ mvn --version 
    Apache Maven 3.9.9 (8e8579a9e76f7d015ee5ec7bfcdc97d260186937)
    Maven home: /home/rest/pkg/apache-maven-3.9.9
    Java version: 17.0.15, vendor: BiSheng, runtime: /usr/lib/jvm/java-17-openjdk-17.0.15.6-4.oe2203sp4.aarch64
    Default locale: en_US, platform encoding: UTF-8
    OS name: "linux", version: "5.10.0-216.0.0.115.oe2203sp4.aarch64", arch: "aarch64", family: "unix"
    ```

#### 安装流程

参考官网文档 [RestApi介绍](https://docs.opengauss.org/zh/docs/latest/tool_and_commandreference/features.html#%E6%94%AF%E6%8C%81%E9%9B%86%E7%BE%A4%E4%BF%A1%E6%81%AF%E6%9F%A5%E8%AF%A2%E5%92%8C%E6%8E%A8%E9%80%81)

#### 参与贡献

1.  Fork 本仓库
2.  新建 Feat_xxx 分支
3.  提交代码
4.  新建 Pull Request


#### 特技

1.  使用 Readme\_XXX.md 来支持不同的语言，例如 Readme\_en.md, Readme\_zh.md
2.  Gitee 官方博客 [blog.gitee.com](https://blog.gitee.com)
3.  你可以 [https://gitee.com/explore](https://gitee.com/explore) 这个地址来了解 Gitee 上的优秀开源项目
4.  [GVP](https://gitee.com/gvp) 全称是 Gitee 最有价值开源项目，是综合评定出的优秀开源项目
5.  Gitee 官方提供的使用手册 [https://gitee.com/help](https://gitee.com/help)
6.  Gitee 封面人物是一档用来展示 Gitee 会员风采的栏目 [https://gitee.com/gitee-stars/](https://gitee.com/gitee-stars/)
