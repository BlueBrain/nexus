package ch.epfl.bluebrain.nexus.tests.iam.types

import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._

final case class AclListing(_results: List[Acl], _total: Long)

final case class Acl(acl: List[AclEntry], _path: String, _rev: Long)

final case class AclEntry(identity: Identity, permissions: Set[Permission])

final case class Permission(name: String, action: String) {
  def value: String = s"$name/$action"
}

sealed trait Identity

case object Anonymous extends Identity

final case class User(realm: String, subject: String) extends Identity
final case class Authenticated(realm: String)         extends Identity
final case class Group(realm: String, group: String)  extends Identity

object AclListing {

  implicit val config: Configuration = Configuration.default.withDiscriminator("@type")

  implicit val identityDecoder: Decoder[Identity]     = deriveConfiguredDecoder[Identity]
  implicit val aclEntryDecoder: Decoder[AclEntry]     = deriveConfiguredDecoder[AclEntry]
  implicit val aclDecoder: Decoder[Acl]               = deriveConfiguredDecoder[Acl]
  implicit val aclListingDecoder: Decoder[AclListing] = deriveConfiguredDecoder[AclListing]
}

object Permission {

  implicit val permissionDecoder: Decoder[Permission] = Decoder.decodeString.emap { value =>
    value.split("/").toList match {
      case name :: action :: Nil => Right(Permission(name, action))
      case _                     => Left(s"Couldn't parse $value into a permission")
    }
  }

  object Acls {
    val name              = "acls"
    val Read: Permission  = Permission(name, "read")
    val Write: Permission = Permission(name, "write")

    val list: List[Permission] = Read :: Write :: Nil
  }

  object Events {
    val name             = "events"
    val Read: Permission = Permission(name, "read")

    val list: List[Permission] = Read :: Nil
  }

  object Files {
    val name              = "files"
    val Write: Permission = Permission(name, "write")

    val list: List[Permission] = Write :: Nil
  }

  object Organizations {
    val name               = "organizations"
    val Create: Permission = Permission(name, "create")
    val Read: Permission   = Permission(name, "read")
    val Write: Permission  = Permission(name, "write")

    val list: List[Permission] = Create :: Read :: Write :: Nil
  }

  object Permissions {
    val name              = "permissions"
    val Read: Permission  = Permission(name, "read")
    val Write: Permission = Permission(name, "write")

    val list: List[Permission] = Read :: Write :: Nil
  }

  object Projects {
    val name               = "projects"
    val Create: Permission = Permission(name, "create")
    val Read: Permission   = Permission(name, "read")
    val Write: Permission  = Permission(name, "write")

    val list: List[Permission] = Create :: Read :: Write :: Nil
  }

  object Realms {
    val name              = "realms"
    val Read: Permission  = Permission(name, "read")
    val Write: Permission = Permission(name, "write")

    val list: List[Permission] = Read :: Write :: Nil
  }

  object Resolvers {
    val name              = "resolvers"
    val Write: Permission = Permission(name, "write")

    val list: List[Permission] = Write :: Nil
  }

  object Resources {
    val name              = "resources"
    val Read: Permission  = Permission(name, "read")
    val Write: Permission = Permission(name, "write")

    val list: List[Permission] = Read :: Write :: Nil
  }

  object Schemas {
    val name              = "schemas"
    val Write: Permission = Permission(name, "write")

    val list: List[Permission] = Write :: Nil
  }

  object Views {
    val name              = "views"
    val Query: Permission = Permission(name, "query")
    val Write: Permission = Permission(name, "write")

    val list: List[Permission] = Query :: Write :: Nil
  }

  object Storages {
    val name              = "storages"
    val Write: Permission = Permission(name, "write")

    val list: List[Permission] = Write :: Nil
  }

  object Archives {
    val name              = "archives"
    val Write: Permission = Permission(name, "write")

    val list: List[Permission] = Write :: Nil
  }

  val minimalPermissions: Set[Permission] =
    (Acls.list ++
      Events.list ++
      Files.list ++
      Organizations.list ++
      Permissions.list ++
      Projects.list ++
      Realms.list ++
      Resolvers.list ++
      Resources.list ++
      Schemas.list ++
      Views.list ++
      Storages.list ++
      Archives.list).toSet

  val adminPermissions: Set[Permission] =
    (Acls.list ++
      Files.list ++
      Organizations.list ++
      Projects.list ++
      Resolvers.list ++
      Resources.list ++
      Schemas.list ++
      Views.list ++
      Storages.list ++
      Archives.list).toSet

}
