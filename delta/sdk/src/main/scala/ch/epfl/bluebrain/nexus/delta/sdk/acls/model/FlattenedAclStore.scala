package ch.epfl.bluebrain.nexus.delta.sdk.acls.model

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label}
import doobie.*
import doobie.syntax.all.*

/**
  * Gives another view of acls where they are flattened by address, identity and permission making scans easier
  */
final class FlattenedAclStore(xas: Transactors) {

  implicit private val identityPut: Put[Identity] = Put[String].contramap {
    case Anonymous                           => "/anonymous"
    case Authenticated(realm)                => s"/realms/${realm.value}/authenticated"
    case Group(group, realm)                 => s"/realms/${realm.value}/groups/$group"
    case User(subject: String, realm: Label) => s"/realms/${realm.value}/groups/$subject"
  }

  def insert(address: AclAddress, acls: Map[Identity, Set[Permission]]): ConnectionIO[Unit] =
    acls.foldLeft(doobie.free.connection.unit) { case (identityAcc, (identity, permissions)) =>
      permissions.foldLeft(identityAcc) { case (permissionAcc, permission) =>
        permissionAcc >>
          sql"""| INSERT INTO flattened_acls(address, identity, permission)
              | VALUES($address, $identity, ${permission.value})""".stripMargin.update.run.void
      }
    }

  def delete(address: AclAddress): ConnectionIO[Unit] =
    sql"""DELETE FROM flattened_acls WHERE address = $address""".update.run.void

  def exists(address: AclAddress, permission: Permission, identities: Set[Identity]): IO[Boolean] = {
    val whereClause = Fragments.whereAnd(
      Fragments.in(fr"address", address.ancestors),
      fr"permission=${permission.value}",
      Fragments.in(fr"identity", NonEmptyList.fromListUnsafe(identities.toList))
    )
    sql"""| SELECT 1 FROM flattened_acls
          | $whereClause
          | LIMIT 1
          |""".stripMargin.query.option.map(_.isDefined).transact(xas.read)
  }

  def fetchAddresses(address: AclAddress, permission: Permission, identities: Set[Identity]): IO[Set[AclAddress]] = {
    val addressClause = address match {
      case AclAddress.Root              => None
      case org: AclAddress.Organization =>
        val pattern = s"$org/%"
        Some(fr"address = $address or address = ${AclAddress.Root.string} or address LIKE $pattern")
      case _                            => Some(Fragments.in(fr"address", address.ancestors))
    }
    val whereClause   = Fragments.whereAndOpt(
      addressClause,
      Some(fr"permission=${permission.value}"),
      Some(Fragments.in(fr"identity", NonEmptyList.fromListUnsafe(identities.toList)))
    )
    sql"""| SELECT DISTINCT address FROM flattened_acls
          | $whereClause
          | order by address
          |""".stripMargin.query[AclAddress].to[Set].transact(xas.read)
  }

  def reset: ConnectionIO[Unit] = sql"TRUNCATE flattened_acls".update.run.void
}
