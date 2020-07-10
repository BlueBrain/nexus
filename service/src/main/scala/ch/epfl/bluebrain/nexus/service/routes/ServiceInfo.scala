package ch.epfl.bluebrain.nexus.service.routes

import akka.actor.ActorSystem
import akka.cluster.{Cluster, MemberStatus}
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import cats.effect.{ContextShift, Effect, IO, Sync}
import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.service.routes.ServiceInfo.{DescriptionValue, StatusValue}
import ch.epfl.bluebrain.nexus.sourcing.projections.Projections.{cassandraDefaultConfigPath, journalConfig}
import ch.epfl.bluebrain.nexus.storage.client.StorageClient
import com.typesafe.scalalogging.Logger
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

trait ServiceInfo[F[_]] {

  /**
    * Evaluate the status value of a service
    */
  def status: F[StatusValue]

  /**
    * Provide the description of the service when available
    */
  def description: F[Option[DescriptionValue]]

}

object ServiceInfo {

  private val log = Logger[ServiceInfo.type]

  /**
    * Enumeration type for possible status values.
    */
  sealed trait StatusValue extends Product with Serializable

  object StatusValue {

    implicit val statusValueEnc: Encoder[StatusValue] = Encoder.encodeString.contramap {
      case Up           => "up"
      case Inaccessible => "inaccessible"
    }

    def apply(value: Boolean): StatusValue =
      if (value) Up else Inaccessible

    /**
      * A service is up and running
      */
    final case object Up extends StatusValue

    /**
      * A service is inaccessible from within the app
      */
    final case object Inaccessible extends StatusValue

  }

  /**
    * A service description.
    *
   * @param name    the name of the service
    * @param version the current version of the service
    */
  final case class DescriptionValue(name: String, version: String)

  object DescriptionValue {
    implicit val descriptionValueEnc: Encoder[DescriptionValue] = deriveEncoder[DescriptionValue]
  }

  /**
    * Build a [[ServiceInfo]] for the akka Cluster status checks.
    */
  def cluster[F[_]](value: Cluster)(implicit F: Sync[F]): ServiceInfo[F] =
    new ServiceInfo[F] {
      override def status: F[StatusValue] =
        F.delay {
          StatusValue(
            !value.isTerminated &&
              value.state.leader.isDefined && value.state.members.nonEmpty &&
              !value.state.members.exists(_.status != MemberStatus.Up) && value.state.unreachable.isEmpty
          )
        }

      override def description: F[Option[DescriptionValue]] = F.pure(None)

    }

  /**
    * Build a [[ServiceInfo]] for the akka Cassandra status checks.
    */
  def cassandra[F[_]](as: ActorSystem)(implicit F: Effect[F]): ServiceInfo[F] =
    new ServiceInfo[F] {
      implicit private val contextShift: ContextShift[IO] = IO.contextShift(as.dispatcher)
      private val keyspace: String                        = journalConfig(as).getString("keyspace")
      private val query                                   = s"SELECT now() FROM $keyspace.messages;"
      private lazy val session                            =
        CassandraSessionRegistry.get(as).sessionFor(CassandraSessionSettings(cassandraDefaultConfigPath))

      override def status: F[StatusValue]                   =
        IO.fromFuture(IO(session.selectOne(query)))
          .as[StatusValue](StatusValue.Up)
          .recover {
            case err =>
              log.error("Error while attempting to query for Cassandra status", err)
              StatusValue.Inaccessible
          }
          .to[F]
      override def description: F[Option[DescriptionValue]] = F.pure(None)

    }

  /**
    * Build a [[ServiceInfo]] for ElasticSearch status checks.
    */
  def elasticSearch[F[_]: Effect](value: ElasticSearchClient[F]): ServiceInfo[F] =
    new ServiceInfo[F] {
      override def status: F[StatusValue] =
        value.serviceDescription.as[StatusValue](StatusValue.Up).recover {
          case err =>
            log.error("Error while attempting to query for ElasticSearch service description", err)
            StatusValue.Inaccessible
        }

      override def description: F[Option[DescriptionValue]] =
        value.serviceDescription.map(sd => Option(DescriptionValue(sd.name, sd.version))).recover {
          case err =>
            log.error("Error while attempting to query for ElasticSearch service description", err)
            Some(DescriptionValue("elasticsearch", "unknown"))
        }

    }

  /**
    * Build a [[ServiceInfo]] for Blazegraph status checks.
    */
  def blazegraph[F[_]: Effect](value: BlazegraphClient[F]): ServiceInfo[F] =
    new ServiceInfo[F] {
      override def status: F[StatusValue] =
        value.serviceDescription.as[StatusValue](StatusValue.Up).recover {
          case err =>
            log.error("Error while attempting to query for Blazegraph service description", err)
            StatusValue.Inaccessible
        }

      override def description: F[Option[DescriptionValue]] =
        value.serviceDescription.map(sd => Option(DescriptionValue(sd.name, sd.version))).recover {
          case err =>
            log.error("Error while attempting to query for Blazegraph service description", err)
            Some(DescriptionValue("blazegraph", "unknown"))
        }
    }

  /**
    * Build a [[ServiceInfo]] for Storage status checks.
    */
  def storage[F[_]: Effect](value: StorageClient[F]): ServiceInfo[F] =
    new ServiceInfo[F] {
      override def status: F[StatusValue] =
        value.serviceDescription.as[StatusValue](StatusValue.Up).recover {
          case err =>
            log.error("Error while attempting to query for Storage service description", err)
            StatusValue.Inaccessible
        }

      override def description: F[Option[DescriptionValue]] =
        value.serviceDescription.map(sd => Option(DescriptionValue(sd.name, sd.version))).recover {
          case err =>
            log.error("Error while attempting to query for Storage service description", err)
            Some(DescriptionValue("storage", "unknown"))
        }
    }

}
