async-maven-proxy
=================
Async Maven Proxy is a caching maven proxy which is written on top of Netty and is pretty light-weight.
I mainly wrote this because sonatype nexus and apache archiva did not work on my NAS to well and I was
in need for some more light-weight solution.

The code itself is written in Scala and uses Netty for the network code itself. Hope you find it
useful.

__How to use it__
First compile it

\# mvn clean compile assembly:single

Then copy the asyncmavenproxy.properties to your prefered directory and adjust if needed. After
this start the proxy via:

\# java -Dasyncmavenproxy.config=/path/to/config/asyncmavenproxy.properties -jar async-maven-proxy-1.0-SNAPSHOT-jar-with-dependencies.jar

Now configure maven to make use of it by add this settings to your ~/.m2/settings.xml

```
  <mirrors>
    <mirror>
      <id>async-maven-proxy</id>
      <mirrorOf>*</mirrorOf>
      <url>http://addressOfProxy:portOfProxy</url>
    </mirror>
  </mirrors>
  <profiles>
    <profile>
      <id>async-maven-proxy</id>
      <repositories>
        <repository>
          <id>central</id>
          <url>http://central</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
     <pluginRepositories>
        <pluginRepository>
          <id>central</id>
          <url>http://central</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>async-maven-proxy</activeProfile>
  </activeProfiles>
```

Enjoy!