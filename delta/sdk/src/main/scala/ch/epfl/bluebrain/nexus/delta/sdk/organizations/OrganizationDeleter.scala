package ch.epfl.bluebrain.nexus.delta.sdk.organizations

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationNonEmpty
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.{PartitionInit, Transactors}
import doobie.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import doobie.util.update.Update0
import doobie.{ConnectionIO, Update}
import org.typelevel.log4cats.{Logger => Log4CatsLogger}

trait OrganizationDeleter {
  def delete(id: Label): IO[Unit]
}

object OrganizationDeleter {

  def apply(xas: Transactors): OrganizationDeleter = new OrganizationDeleter {

    private val log: Log4CatsLogger[IO] = Logger[OrganizationDeleter]

    def delete(id: Label): IO[Unit] =
      for {
        canDelete <- orgIsEmpty(id)
        _         <- if (canDelete) log.info(s"Deleting empty organization $id") *> deleteAll(id)
                     else log.error(s"Failed to delete empty organization $id") *> IO.raiseError(OrganizationNonEmpty(id))
      } yield ()

    private def deleteAll(id: Label): IO[Unit] =
      (for {
        _ <- List("scoped_events", "scoped_states").traverse(dropPartition(id, _))
        _ <- List("global_events", "global_states").traverse(deleteGlobal(id, _))
      } yield ()).transact(xas.write).void

    private def dropPartition(id: Label, table: String): ConnectionIO[Unit] =
      Update0(s"DROP TABLE IF EXISTS ${PartitionInit.orgPartition(table, id)}", None).run.void

    private def deleteGlobal(id: Label, table: String): ConnectionIO[Unit] =
      for {
        _ <- deleteGlobalQuery(Organizations.encodeId(id), Organizations.entityType, table)
        _ <- deleteGlobalQuery(Acls.encodeId(AclAddress.fromOrg(id)), Acls.entityType, table)
      } yield ()

    private def deleteGlobalQuery(id: IriOrBNode.Iri, tpe: EntityType, table: String): ConnectionIO[Unit] =
      Update[(EntityType, IriOrBNode.Iri)](s"DELETE FROM $table WHERE" ++ " type = ? AND id = ?").run((tpe, id)).void

    private def orgIsEmpty(id: Label): IO[Boolean] =
      sql"SELECT type from scoped_events WHERE org = $id LIMIT 1"
        .query[Label]
        .option
        .map(_.isEmpty)
        .transact(xas.read)
  }

}
