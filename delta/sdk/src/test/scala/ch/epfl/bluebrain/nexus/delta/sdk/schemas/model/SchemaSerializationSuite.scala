package ch.epfl.bluebrain.nexus.delta.sdk.schemas.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.jsonOpsSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.generators.SchemaGen
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaEvent.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, Tags}

import java.time.Instant

class SchemaSerializationSuite extends SerializationSuite {

  private val instant: Instant = Instant.EPOCH
  private val realm: Label     = Label.unsafe("myrealm")

  private val subject: Subject = User("username", realm)
  private val org: Label       = Label.unsafe("myorg")
  private val proj: Label      = Label.unsafe("myproj")
  private val projectRef       = ProjectRef(org, proj)
  private val myId             = nxv + "myId"

  private val schema = SchemaGen.schema(
    myId,
    projectRef,
    jsonContentOf("resources/schema.json")
      .addContext(contexts.shacl, contexts.schemasMetadata) deepMerge json"""{"@id": "$myId"}"""
  )

  private val created      =
    SchemaCreated(
      myId,
      projectRef,
      schema.source,
      schema.compacted,
      schema.expanded,
      1,
      instant,
      subject
    )
  private val updated      =
    SchemaUpdated(
      myId,
      projectRef,
      schema.source,
      schema.compacted,
      schema.expanded,
      2,
      instant,
      subject
    )
  private val refreshed    =
    SchemaRefreshed(
      myId,
      projectRef,
      schema.compacted,
      schema.expanded,
      2,
      instant,
      subject
    )
  private val tagged       =
    SchemaTagAdded(
      myId,
      projectRef,
      1,
      UserTag.unsafe("mytag"),
      3,
      instant,
      subject
    )
  private val tagDeleted   =
    SchemaTagDeleted(
      myId,
      projectRef,
      UserTag.unsafe("mytag"),
      3,
      instant,
      subject
    )
  private val deprecated   =
    SchemaDeprecated(
      myId,
      projectRef,
      4,
      instant,
      subject
    )
  private val undeprecated =
    SchemaUndeprecated(
      myId,
      projectRef,
      5,
      instant,
      subject
    )

  private val schemasMapping = List(
    (created, jsonContentOf("schemas/schema-created.json")),
    (updated, jsonContentOf("schemas/schema-updated.json")),
    (refreshed, jsonContentOf("schemas/schema-refreshed.json")),
    (tagged, jsonContentOf("schemas/schema-tagged.json")),
    (tagDeleted, jsonContentOf("schemas/schema-tag-deleted.json")),
    (deprecated, jsonContentOf("schemas/schema-deprecated.json")),
    (undeprecated, jsonContentOf("schemas/schema-undeprecated.json"))
  )

  schemasMapping.foreach { case (event, json) =>
    test(s"Correctly serialize ${event.getClass.getSimpleName}") {
      assertOutput(SchemaEvent.serializer, event, json)
    }

    test(s"Correctly deserialize ${event.getClass.getSimpleName}") {
      assertEquals(SchemaEvent.serializer.codec.decodeJson(json), Right(event))
    }
  }

  private val state = SchemaState(
    myId,
    projectRef,
    schema.source,
    schema.compacted,
    schema.expanded,
    rev = 2,
    deprecated = false,
    Tags(UserTag.unsafe("mytag") -> 3),
    createdAt = instant,
    createdBy = subject,
    updatedAt = instant,
    updatedBy = subject
  )

  private val jsonState = jsonContentOf("schemas/schema-state.json")

  test(s"Correctly serialize a SchemaState") {
    assertOutput(SchemaState.serializer, state, jsonState)
  }

  test(s"Correctly deserialize a SchemaState") {
    assertEquals(SchemaState.serializer.codec.decodeJson(jsonState), Right(state))
  }
}
