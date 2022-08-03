package ch.epfl.bluebrain.nexus.delta.sdk.schemas.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.jsonOpsSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.generators.SchemaGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaEvent._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.Json

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

  val schemasMapping: Map[SchemaEvent, Json] = Map(
    SchemaCreated(
      myId,
      projectRef,
      schema.source,
      schema.compacted,
      schema.expanded,
      1,
      instant,
      subject
    ) -> jsonContentOf("/schemas/schema-created.json"),
    SchemaUpdated(
      myId,
      projectRef,
      schema.source,
      schema.compacted,
      schema.expanded,
      2,
      instant,
      subject
    ) -> jsonContentOf("/schemas/schema-updated.json"),
    SchemaTagAdded(
      myId,
      projectRef,
      1,
      UserTag.unsafe("mytag"),
      3,
      instant,
      subject
    ) -> jsonContentOf("/schemas/schema-tagged.json"),
    SchemaTagDeleted(
      myId,
      projectRef,
      UserTag.unsafe("mytag"),
      3,
      instant,
      subject
    ) -> jsonContentOf("/schemas/schema-tag-deleted.json"),
    SchemaDeprecated(
      myId,
      projectRef,
      4,
      instant,
      subject
    ) -> jsonContentOf("/schemas/schema-deprecated.json")
  )

  schemasMapping.foreach { case (event, json) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertEquals(SchemaEvent.serializer.codec(event), json)
    }
  }

  schemasMapping.foreach { case (event, json) =>
    test(s"Correctly deserialize ${event.getClass.getName}") {
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

  private val jsonState = jsonContentOf("/schemas/schema-state.json")

  test(s"Correctly serialize a SchemaState") {
    assertEquals(SchemaState.serializer.codec(state), jsonState)
  }

  test(s"Correctly deserialize a SchemaState") {
    assertEquals(SchemaState.serializer.codec.decodeJson(jsonState), Right(state))
  }
}
