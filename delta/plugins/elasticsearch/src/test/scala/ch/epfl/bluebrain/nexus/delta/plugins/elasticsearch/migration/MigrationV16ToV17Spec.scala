package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.migration

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.migration.MigrationV16ToV17._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent.{ElasticSearchViewCreated, ElasticSearchViewUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers}
import io.circe.Printer
import io.circe.syntax.EncoderOps
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Inspectors, OptionValues}

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class MigrationV16ToV17Spec
    extends AnyWordSpec
    with Matchers
    with Inspectors
    with CirceLiteral
    with OptionValues
    with EitherValuable
    with IOValues
    with TestHelpers {

  private val printer: Printer = Printer.noSpaces.copy(dropNullValues = true)

  "Migrating" should {
    "skip events already migrated events / aggregated views events / tag evemts / deprecated events" in {
      forAll(
        List(
          "serialization/aggregate-view-created.json",
          "serialization/aggregate-view-updated.json",
          "serialization/indexing-view-created.json",
          "serialization/indexing-view-updated.json",
          "serialization/view-deprecated.json",
          "serialization/view-tag-added.json"
        )
      ) { file =>
        migrateEvent(printer.print(jsonContentOf(file)).getBytes(StandardCharsets.UTF_8)) shouldEqual Right(None)
      }
    }

    val create =
      jobj"""
          {
            "id" : "https://bluebrain.github.io/nexus/vocabulary/indexing-view",
            "project" : "myorg/myproj",
            "uuid" : "f8468909-a797-4b10-8b5f-000cba337bfa",
            "value" : {
              "resourceSchemas" : [
                "https://bluebrain.github.io/nexus/vocabulary/some-schema"
              ],
              "resourceTypes" : [
                "https://bluebrain.github.io/nexus/vocabulary/SomeType"
              ],
              "resourceTag" : "some.tag",
              "sourceAsText" : true,
              "includeMetadata" : false,
              "includeDeprecated" : false,
              "mapping" : {
                "properties" : {

                }
              },
              "settings" : {
                "analysis" : {

                }
              },
              "permission" : "my/permission",
              "@type" : "IndexingElasticSearchViewValue"
            },
            "source" : {
              "resourceSchemas" : [
                "https://bluebrain.github.io/nexus/vocabulary/some-schema"
              ],
              "resourceTypes" : [
                "https://bluebrain.github.io/nexus/vocabulary/SomeType"
              ],
              "resourceTag" : "some.tag",
              "sourceAsText" : true,
              "includeMetadata" : false,
              "includeDeprecated" : false,
              "mapping" : {
                "properties" : {

                }
              },
              "settings" : {
                "analysis" : {

                }
              },
              "permission" : "my/permission",
              "@type" : "ElasticSearchView",
              "@id" : "https://bluebrain.github.io/nexus/vocabulary/indexing-view"
            },
            "rev" : 1,
            "instant" : "1970-01-01T00:00:00Z",
            "subject" : {
              "subject" : "username",
              "realm" : "myrealm",
              "@type" : "User"
            },
            "@type" : "ElasticSearchViewCreated"
          }
            """

    val value = create("value").flatMap(_.asObject).value

    "migrate old create event" in {
      migrateEvent(printer.print(create.asJson).getBytes(StandardCharsets.UTF_8)).rightValue.value shouldEqual
        ElasticSearchViewCreated(
          nxv + "indexing-view",
          ProjectRef.unsafe("myorg", "myproj"),
          UUID.fromString("f8468909-a797-4b10-8b5f-000cba337bfa"),
          IndexingElasticSearchViewValue(
            resourceTag = Some(TagLabel.unsafe("some.tag")),
            pipeline = List(
              FilterDeprecated(),
              FilterByType(Set(nxv + "SomeType")),
              FilterBySchema(Set(nxv + "some-schema")),
              DiscardMetadata(),
              DefaultLabelPredicates(),
              SourceAsText()
            ),
            mapping = value("mapping").value.asObject,
            settings = value("settings").value.asObject,
            None,
            Permission.unsafe("my/permission")
          ),
          create("source").value,
          1L,
          Instant.EPOCH,
          User("username", Label.unsafe("myrealm"))
        )
    }

    "migrate old update event" in {
      val update = create.deepMerge(jobj"""{ "@type": "ElasticSearchViewUpdated", "rev" : 2 }""")
      migrateEvent(printer.print(update.asJson).getBytes(StandardCharsets.UTF_8)).rightValue.value shouldEqual
        ElasticSearchViewUpdated(
          nxv + "indexing-view",
          ProjectRef.unsafe("myorg", "myproj"),
          UUID.fromString("f8468909-a797-4b10-8b5f-000cba337bfa"),
          IndexingElasticSearchViewValue(
            resourceTag = Some(TagLabel.unsafe("some.tag")),
            pipeline = List(
              FilterDeprecated(),
              FilterByType(Set(nxv + "SomeType")),
              FilterBySchema(Set(nxv + "some-schema")),
              DiscardMetadata(),
              DefaultLabelPredicates(),
              SourceAsText()
            ),
            mapping = value("mapping").value.asObject,
            settings = value("settings").value.asObject,
            None,
            Permission.unsafe("my/permission")
          ),
          update("source").value,
          2L,
          Instant.EPOCH,
          User("username", Label.unsafe("myrealm"))
        )
    }
  }
}
