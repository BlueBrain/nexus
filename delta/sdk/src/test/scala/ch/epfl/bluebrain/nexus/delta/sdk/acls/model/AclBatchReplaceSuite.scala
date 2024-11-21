package ch.epfl.bluebrain.nexus.delta.sdk.acls.model

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{acls, projects, resources}

class AclBatchReplaceSuite extends NexusSuite {

  test("Deserialize properly the json payload") {
    val payload = json"""
      {
        "/" : [
          {
            "permissions": [
              "acls/read",
              "acls/write"
            ],
            "identity": {
              "realm": "realm",
              "subject": "superuser"
            }
          },
          {
            "permissions": [
              "projects/read",
              "projects/write"
            ],
            "identity": {
              "realm": "realm",
              "group": "admins"
            }
          }
        ],
        "/bbp": [
          {
            "permissions": [
              "resources/read",
              "resources/write"
            ],
            "identity": {
              "realm": "realm",
              "group": "bbp-users"
            }
          }
        ],
        "/bbp/atlas": [
          {
            "permissions": [
              "resources/read",
              "resources/write"
            ],
            "identity": {
              "realm": "realm",
              "group": "atlas-users"
            }
          }
        ]
      }"""

    val realm    = Label.unsafe("realm")
    val bbp      = Label.unsafe("bbp")
    val atlas    = Label.unsafe("atlas")
    val expected = AclBatchReplace(
      Vector(
        Acl(
          AclAddress.Root,
          User("superuser", realm)                                      -> Set(acls.read, acls.write),
          Group("admins", realm)                                        -> Set(projects.read, projects.write)
        ),
        Acl(AclAddress.Organization(bbp), Group("bbp-users", realm)     -> Set(resources.read, resources.write)),
        Acl(AclAddress.Project(bbp, atlas), Group("atlas-users", realm) -> Set(resources.read, resources.write))
      )
    )

    assertEquals(
      payload.as[AclBatchReplace],
      Right(expected)
    )
  }

}
