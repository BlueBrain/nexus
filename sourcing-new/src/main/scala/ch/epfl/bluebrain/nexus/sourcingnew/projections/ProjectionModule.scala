package ch.epfl.bluebrain.nexus.sourcingnew.projections


import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import cats.effect.{Async, ContextShift}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.cassandra.{CassandraProjection, CassandraSchemaManager}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.jdbc.{JdbcConfig, JdbcProjection, JdbcSchemaManager}
import com.typesafe.config.Config
import distage.{Axis, ModuleDef, Tag, TagK}
import io.circe.{Decoder, Encoder}

object Persistence extends Axis {
  case object Cassandra extends AxisValueDef
  case object Postgres extends AxisValueDef
}

final class ProjectionModule[F[_]: ContextShift: Async: TagK, A: Encoder: Decoder: Tag] extends ModuleDef {

  make[SchemaManager[F]].tagged(Persistence.Cassandra).from {
    (cassandraSession: CassandraSession, journalConfig: Config, actorSystem: ActorSystem[Nothing]) =>
      new CassandraSchemaManager[F](cassandraSession, journalConfig, actorSystem)

  }
  make[Projection[F, A]].tagged(Persistence.Cassandra).from {
    (cassandraSession: CassandraSession, journalConfig: Config, actorSystem: ActorSystem[Nothing]) =>
      new CassandraProjection[F, A](cassandraSession, journalConfig, actorSystem)

  }

  make[SchemaManager[F]].tagged(Persistence.Postgres).from {
    (jdbcConfig: JdbcConfig) =>
      new JdbcSchemaManager[F](jdbcConfig)
  }
  make[Projection[F,A]].tagged(Persistence.Postgres).from {
    (jdbcConfig: JdbcConfig, journalConfig: Config) =>
      new JdbcProjection[F, A](jdbcConfig, journalConfig)
  }

}

object ProjectionModule {
  final def apply[F[_]: ContextShift: Async: TagK, A: Encoder: Decoder: Tag]: ProjectionModule[F, A] =
    new ProjectionModule[F, A]
}
