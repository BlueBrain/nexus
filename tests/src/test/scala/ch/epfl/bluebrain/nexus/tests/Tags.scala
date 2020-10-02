package ch.epfl.bluebrain.nexus.tests

import org.scalatest.Tag

trait Tags

/**
  * Scalatest tags
  */
object Tags extends Tags {

  object RealmsTag      extends Tag("Realms")
  object PermissionsTag extends Tag("Permissions")
  object AclsTag        extends Tag("Acls")

  object OrgsTag     extends Tag("Orgs")
  object ProjectsTag extends Tag("Projects")

  object ArchivesTag       extends Tag("Archives")
  object ResourcesTag      extends Tag("Resources")
  object ViewsTag          extends Tag("Views")
  object CompositeViewsTag extends Tag("CompositeViews")
  object EventsTag         extends Tag("Events")

  object StorageTag extends Tag("Storage")

  object AppInfoTag extends Tag("AppInfo")
}
