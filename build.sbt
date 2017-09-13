import java.util.regex.Pattern

import com.typesafe.sbt.packager.MappingsHelper
import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}

lazy val root = project.in(file("."))
  .enablePlugins(ParadoxPlugin, com.typesafe.sbt.packager.docker.DockerPlugin)
  .settings(
    name                          := "docs",
    paradoxTheme                  := Some(builtinParadoxTheme("generic")),
    paradoxProperties in Compile ++= Map("extref.service.base_url" -> "../%s"),
    maintainer                    := "Nexus Team <noreply@epfl.ch>",
    dockerBaseImage               := "nginx:1.11",
    daemonUser                    := "root",
    dockerRepository              := sys.env.get("DOCKER_REGISTRY"),
    dockerAlias                   := DockerAlias(dockerRepository.value, None, (packageName in Docker).value, Some((version in Docker).value)),
    dockerBuildOptions           ++= {
      val options = for {
        stringArgs <- sys.env.get("DOCKER_BUILD_ARGS").toList
        arg        <- stringArgs.split(Pattern.quote("|"))
        pair       <- List("--build-arg", arg)
      } yield pair
      options
    },
    dockerUpdateLatest            := !isSnapshot.value,
    dockerCommands                := Seq(
      Cmd("FROM",       dockerBaseImage.value),
      Cmd("MAINTAINER", maintainer.value),
      Cmd("USER",       daemonUser.value),
      Cmd("RUN",        "rm -rf /etc/nginx/conf.d && mkdir -p /etc/nginx/conf.d &&  chown -R root:0 /etc/nginx/conf.d && chmod g+rwx /etc/nginx/conf.d"),
      Cmd("RUN",        "mkdir -p /var/cache/nginx && chown -R root:0 /var/cache/nginx && chmod g+rwx /var/cache/nginx"),
      Cmd("RUN",        "touch /var/run/nginx.pid && chown root:0 /var/run/nginx.pid && chmod g+rwx /var/run/nginx.pid"),
      Cmd("RUN",        "ln -sf /dev/stdout /var/log/nginx/access.log && ln -sf /dev/stderr /var/log/nginx/error.log"),
      Cmd("ADD",        "default.conf.template /etc/nginx/conf.d"),
      Cmd("ADD",       s"${(paradox in Compile).value.getName} /usr/share/nginx/html"),
      Cmd("RUN",        "chown -R root:0 /usr/share/nginx/html && chmod g+rwx /usr/share/nginx/html"),
      Cmd("ENV",       s"LOCATION /docs"),
      Cmd("ENV",       s"SERVER_NAME localhost"),
      Cmd("EXPOSE",     "8080"),
      Cmd("CMD",        "envsubst '$LOCATION $SERVER_NAME' < /etc/nginx/conf.d/default.conf.template > /etc/nginx/conf.d/default.conf && nginx -g 'daemon off;'")
    ),
    mappings in Docker ++=
      MappingsHelper.contentOf(sourceDirectory.value / "main" / "resources") ++
      MappingsHelper.directory((paradox in Compile).value),
    publishLocal := {
      Def.taskDyn {
        if (!isSnapshot.value) Def.task { (publishLocal in Docker).value }
        else Def.task { () }
      }.value
    },
    publish := {
      Def.taskDyn {
        if (!isSnapshot.value) Def.task { (publish in Docker).value }
        else Def.task { () }
      }.value
    })

addCommandAlias("review", ";clean;paradox")
addCommandAlias("rel",    ";release with-defaults")
