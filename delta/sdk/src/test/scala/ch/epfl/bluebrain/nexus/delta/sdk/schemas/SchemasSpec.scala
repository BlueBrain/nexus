package ch.epfl.bluebrain.nexus.delta.sdk.schemas

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidateShacl
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.{IncorrectRev, InvalidSchema, ReservedSchemaId, ResourceAlreadyExists, RevisionNotFound, SchemaIsDeprecated, SchemaIsNotDeprecated, SchemaNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.{SchemaCommand, SchemaEvent, SchemaState}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

import java.time.Instant

class SchemasSpec extends CatsEffectSpec with Fixtures {

  implicit override val api: JsonLdApi = JsonLdJavaApi.lenient

  "The Schemas state machine" when {

    val epoch   = Instant.EPOCH
    val time2   = Instant.ofEpochMilli(10L)
    val subject = User("myuser", Label.unsafe("myrealm"))

    val project = ProjectGen.resourceFor(ProjectGen.project("myorg", "myproject", base = nxv.base))

    val myId      = nxv + "myschema"
    val source    = jsonContentOf("resources/schema.json").addContext(contexts.shacl, contexts.schemasMetadata)
    val schema    = SchemaGen.schema(myId, project.value.ref, source)
    val compacted = schema.compacted
    val expanded  = schema.expanded

    val sourceUpdated = source.replace("targetClass" -> "nxv:Custom", "nxv:Other")
    val schemaUpdated = SchemaGen.schema(myId, project.value.ref, sourceUpdated)

    val eval: (Option[SchemaState], SchemaCommand) => IO[SchemaEvent] =
      evaluate(ValidateSchema(ValidateShacl(rcr).accepted), clock)

    "evaluating an incoming command" should {

      "create a new event from a CreateSchema command" in {
        eval(None, CreateSchema(myId, project.value.ref, source, compacted, expanded, subject)).accepted shouldEqual
          SchemaCreated(myId, project.value.ref, source, compacted, expanded, 1, epoch, subject)
      }

      "create a new event from a UpdateSchema command" in {
        val compacted = schemaUpdated.compacted
        val expanded  = schemaUpdated.expanded

        eval(
          Some(SchemaGen.currentState(schema)),
          UpdateSchema(myId, project.value.ref, sourceUpdated, compacted, expanded, 1, subject)
        ).accepted shouldEqual
          SchemaUpdated(myId, project.value.ref, sourceUpdated, compacted, expanded, 2, epoch, subject)
      }

      "create a new event from a TagSchema command" in {
        eval(
          Some(SchemaGen.currentState(schema, rev = 2)),
          TagSchema(myId, project.value.ref, 1, UserTag.unsafe("myTag"), 2, subject)
        ).accepted shouldEqual
          SchemaTagAdded(myId, project.value.ref, 1, UserTag.unsafe("myTag"), 3, epoch, subject)
      }

      "create a new event from a DeleteSchemaTag command" in {
        val tag = UserTag.unsafe("myTag")
        eval(
          Some(SchemaGen.currentState(schema, rev = 2).copy(tags = Tags(tag -> 1))),
          DeleteSchemaTag(myId, project.value.ref, tag, 2, subject)
        ).accepted shouldEqual
          SchemaTagDeleted(myId, project.value.ref, tag, 3, epoch, subject)
      }

      "create a new event from a DeprecateSchema command" in {
        val current = SchemaGen.currentState(schema, rev = 2)

        eval(Some(current), DeprecateSchema(myId, project.value.ref, 2, subject)).accepted shouldEqual
          SchemaDeprecated(myId, project.value.ref, 3, epoch, subject)
      }

      "create a new event from an UndeprecateSchema command" in {
        val current = SchemaGen.currentState(schema, rev = 2, deprecated = true)
        eval(Some(current), UndeprecateSchema(myId, project.value.ref, 2, subject)).accepted shouldEqual
          SchemaUndeprecated(myId, project.value.ref, 3, epoch, subject)
      }

      "reject with IncorrectRev" in {
        val current = SchemaGen.currentState(schema)
        val list    = List(
          current -> UpdateSchema(myId, project.value.ref, source, compacted, expanded, 2, subject),
          current -> TagSchema(myId, project.value.ref, 1, UserTag.unsafe("tag"), 2, subject),
          current -> DeleteSchemaTag(myId, project.value.ref, UserTag.unsafe("tag"), 2, subject),
          current -> DeprecateSchema(myId, project.value.ref, 2, subject),
          current -> UndeprecateSchema(myId, project.value.ref, 2, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(Some(state), cmd).rejected shouldEqual IncorrectRev(provided = 2, expected = 1)
        }
      }

      "reject with InvalidSchema" in {
        val current     = SchemaGen.currentState(schema)
        val wrongSource = source.replace("minCount" -> 1, "wrong")
        val wrongSchema = SchemaGen.schema(myId, project.value.ref, wrongSource)
        val compacted   = wrongSchema.compacted
        val expanded    = wrongSchema.expanded
        val list        = List(
          None          -> CreateSchema(myId, project.value.ref, wrongSource, compacted, expanded, subject),
          Some(current) -> UpdateSchema(myId, project.value.ref, wrongSource, compacted, expanded, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[InvalidSchema]
        }
      }

      "reject with ResourceAlreadyExists (schema)" in {
        val current = SchemaGen.currentState(schema)
        eval(Some(current), CreateSchema(myId, project.value.ref, source, compacted, expanded, subject))
          .rejectedWith[ResourceAlreadyExists]
      }

      "reject with SchemaNotFound" in {
        val list = List(
          None -> UpdateSchema(myId, project.value.ref, source, compacted, expanded, 1, subject),
          None -> TagSchema(myId, project.value.ref, 1, UserTag.unsafe("myTag"), 1, subject),
          None -> DeleteSchemaTag(myId, project.value.ref, UserTag.unsafe("myTag"), 1, subject),
          None -> DeprecateSchema(myId, project.value.ref, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[SchemaNotFound]
        }
      }

      "reject with SchemaIsDeprecated" in {
        val current = SchemaGen.currentState(schema, deprecated = true)
        val list    = List(
          current -> UpdateSchema(myId, project.value.ref, source, compacted, expanded, 1, subject),
          current -> DeprecateSchema(myId, project.value.ref, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(Some(state), cmd).rejectedWith[SchemaIsDeprecated]
        }
      }

      "reject with SchemaIsNotDeprecated" in {
        val current = SchemaGen.currentState(schema, deprecated = false)
        eval(Some(current), UndeprecateSchema(myId, project.value.ref, 1, subject)).rejectedWith[SchemaIsNotDeprecated]
      }

      "reject with RevisionNotFound" in {
        eval(
          Some(SchemaGen.currentState(schema)),
          TagSchema(myId, project.value.ref, 3, UserTag.unsafe("myTag"), 1, subject)
        ).rejected shouldEqual RevisionNotFound(provided = 3, current = 1)
      }

      "reject with ReservedSchemaId" in {
        val reserved = schemas + "myid"

        eval(
          None,
          CreateSchema(reserved, project.value.ref, source, compacted, expanded, subject)
        ).rejected shouldEqual ReservedSchemaId(reserved)
      }

    }

    "producing next state" should {
      val tags    = Tags(UserTag.unsafe("a") -> 1)
      val current = SchemaGen.currentState(schema.copy(tags = tags))

      "create a new SchemaCreated state" in {
        next(
          None,
          SchemaCreated(myId, project.value.ref, source, compacted, expanded, 1, epoch, subject)
        ).value shouldEqual
          current.copy(
            createdAt = epoch,
            createdBy = subject,
            updatedAt = epoch,
            updatedBy = subject,
            tags = Tags.empty
          )

        next(
          Some(current),
          SchemaCreated(myId, project.value.ref, source, compacted, expanded, 1, time2, subject)
        ) shouldEqual None
      }

      "create a new SchemaUpdated state" in {
        next(
          None,
          SchemaUpdated(myId, project.value.ref, source, compacted, expanded, 1, time2, subject)
        ) shouldEqual None

        next(
          Some(current),
          SchemaUpdated(myId, project.value.ref, sourceUpdated, compacted, expanded, 2, time2, subject)
        ).value shouldEqual
          current.copy(rev = 2, source = sourceUpdated, updatedAt = time2, updatedBy = subject)
      }

      "create new SchemaTagAdded state" in {
        val tag = UserTag.unsafe("tag")
        next(
          None,
          SchemaTagAdded(myId, project.value.ref, 1, tag, 2, time2, subject)
        ) shouldEqual None

        next(Some(current), SchemaTagAdded(myId, project.value.ref, 1, tag, 2, time2, subject)).value shouldEqual
          current.copy(rev = 2, updatedAt = time2, updatedBy = subject, tags = tags + (tag -> 1))
      }

      "create new SchemaDeprecated state" in {
        next(None, SchemaDeprecated(myId, project.value.ref, 1, time2, subject)) shouldEqual None

        next(Some(current), SchemaDeprecated(myId, project.value.ref, 2, time2, subject)).value shouldEqual
          current.copy(rev = 2, deprecated = true, updatedAt = time2, updatedBy = subject)
      }

      "create new SchemaUndeprecated state" in {
        next(None, SchemaUndeprecated(myId, project.value.ref, 1, time2, subject)) shouldEqual None

        val deprecatedState = current.copy(rev = 2, deprecated = true)
        next(Some(deprecatedState), SchemaUndeprecated(myId, project.value.ref, 2, time2, subject)).value shouldEqual
          current.copy(rev = 2, deprecated = false, updatedAt = time2, updatedBy = subject)
      }
    }
  }
}
