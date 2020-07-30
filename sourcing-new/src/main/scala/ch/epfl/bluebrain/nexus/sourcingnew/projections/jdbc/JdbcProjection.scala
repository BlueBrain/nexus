package ch.epfl.bluebrain.nexus.sourcingnew.projections.jdbc

import akka.persistence.query.Offset
import cats.effect.{Async, ContextShift}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcingnew.projections.instances._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.{Projection, ProjectionProgress}
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

final case class JdbcConfig(host: String,
                            port: Int,
                            database: String,
                            username: String,
                            password: String,
                            driver: String = "org.postgresql.Driver") {
  def url: String = s"jdbc:postgresql://$host:$port/$database?stringtype=unspecified"
}

class JdbcProjection[F[_]: ContextShift: Async,
                     A: Encoder: Decoder](xa: Transactor[F])
  extends Projection[F, A] {

  /**
    * Records progress against a projection identifier.
    *
    * @param id       the projection identifier
    * @param progress the offset to record
    * @return a future () value
    */
  override def recordProgress(id: String, progress: ProjectionProgress): F[Unit] =
    sql"""INSERT into projections_progress(projection_id, progress)
         |VALUES($id, ${progress.asJson.noSpaces})
         |ON CONFLICT (projection_id) DO UPDATE SET progress=EXCLUDED.progress""".stripMargin.
      update.run.transact(xa).map(_ => ())

  /**
    * Retrieves the progress for the specified projection projectionId. If there is no record of progress
    * the [[ProjectionProgress.NoProgress]] is returned.
    *
    * @param id an unique projectionId for a projection
    * @return a future progress value for the specified projection projectionId
    */
  override def progress(id: String): F[ProjectionProgress] =
    sql"SELECT progress FROM projections_progress WHERE projection_id = $id"
      .query[String].option.transact(xa).flatMap {
        Projection.decodeOption[ProjectionProgress, F](_, NoProgress)
      }

  /**
    * Record a specific event against a index failures log projectionId.
    *
    * @param id            the project identifier
    * @param persistenceId the persistenceId to record
    * @param sequenceNr    the sequence number to record
    * @param offset        the offset to record
    * @param value         the value to be recorded
    */
  override def recordFailure(id: String, persistenceId: String, sequenceNr: Long, offset: Offset, value: A): F[Unit] =
    sql"""INSERT INTO projections_failures (projection_id, akka_offset, persistence_id, sequence_nr, value)
         |VALUES ($id, ${offset.asJson.noSpaces}, $persistenceId, $sequenceNr, ${value.asJson.noSpaces})
         |ON CONFLICT DO NOTHING""".stripMargin.update.run.transact(xa).map(_ => ())

  /**
    * An event stream for all failures recorded for a projection.
    *
    * @param id the projection identifier
    * @return a source of the failed events
    */
  override def failures(id: String): fs2.Stream[F, (A, Offset)] =
    sql"""SELECT value, akka_offset from projections_failures WHERE projection_id = $id ORDER BY ordering"""
      .query[(String, String)].stream
      .transact(xa).mapFilter(Projection.decodeTuple[A, Offset])
}
