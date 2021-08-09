package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.Schemas.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.testkit._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant

class SchemasSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValuable
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers
    with CirceLiteral
    with OptionValues
    with Fixtures {

  "The Schemas state machine" when {
    implicit val sc: Scheduler = Scheduler.global
    val epoch                  = Instant.EPOCH
    val time2                  = Instant.ofEpochMilli(10L)
    val subject                = User("myuser", Label.unsafe("myrealm"))

    val project = ProjectGen.resourceFor(ProjectGen.project("myorg", "myproject", base = nxv.base))

    val myId      = nxv + "myschema"
    val source    = jsonContentOf("resources/schema.json").addContext(contexts.shacl, contexts.schemasMetadata)
    val schema    = SchemaGen.schema(myId, project.value.ref, source)
    val compacted = schema.compacted
    val expanded  = schema.expanded

    val sourceUpdated = source.replace("targetClass" -> "nxv:Custom", "nxv:Other")
    val schemaUpdated = SchemaGen.schema(myId, project.value.ref, sourceUpdated)

    "evaluating an incoming command" should {
      val eval = evaluate((_, _) => IO.unit)(_, _)
      "create a new event from a CreateSchema command" in {
        eval(Initial, CreateSchema(myId, project.value.ref, source, compacted, expanded, subject)).accepted shouldEqual
          SchemaCreated(myId, project.value.ref, source, compacted, expanded, 1L, epoch, subject)
      }

      "create a new event from a UpdateSchema command" in {
        val compacted = schemaUpdated.compacted
        val expanded  = schemaUpdated.expanded

        eval(
          SchemaGen.currentState(schema),
          UpdateSchema(myId, project.value.ref, sourceUpdated, compacted, expanded, 1L, subject)
        ).accepted shouldEqual
          SchemaUpdated(myId, project.value.ref, sourceUpdated, compacted, expanded, 2L, epoch, subject)
      }

      "create a new event from a TagSchema command" in {
        eval(
          SchemaGen.currentState(schema, rev = 2L),
          TagSchema(myId, project.value.ref, 1L, TagLabel.unsafe("myTag"), 2L, subject)
        ).accepted shouldEqual
          SchemaTagAdded(myId, project.value.ref, 1L, TagLabel.unsafe("myTag"), 3L, epoch, subject)
      }

      "create a new event from a DeprecateSchema command" in {

        val current = SchemaGen.currentState(schema, rev = 2L)

        eval(current, DeprecateSchema(myId, project.value.ref, 2L, subject)).accepted shouldEqual
          SchemaDeprecated(myId, project.value.ref, 3L, epoch, subject)
      }

      "reject with IncorrectRev" in {
        val current = SchemaGen.currentState(schema)
        val list    = List(
          current -> UpdateSchema(myId, project.value.ref, source, compacted, expanded, 2L, subject),
          current -> TagSchema(myId, project.value.ref, 1L, TagLabel.unsafe("tag"), 2L, subject),
          current -> DeprecateSchema(myId, project.value.ref, 2L, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejected shouldEqual IncorrectRev(provided = 2L, expected = 1L)
        }
      }

      "reject with InvalidSchema" in {
        val current     = SchemaGen.currentState(schema)
        val wrongSource = source.replace("minCount" -> 1, "wrong")
        val wrongSchema = SchemaGen.schema(myId, project.value.ref, wrongSource)
        val compacted   = wrongSchema.compacted
        val expanded    = wrongSchema.expanded
        val list        = List(
          Initial -> CreateSchema(myId, project.value.ref, wrongSource, compacted, expanded, subject),
          current -> UpdateSchema(myId, project.value.ref, wrongSource, compacted, expanded, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[InvalidSchema]
        }
      }

      "reject with ResourceAlreadyExists (schema)" in {
        val current = SchemaGen.currentState(schema)
        eval(current, CreateSchema(myId, project.value.ref, source, compacted, expanded, subject))
          .rejectedWith[ResourceAlreadyExists]
      }

      "reject with ResourceAlreadyExists" in {
        val command = CreateSchema(myId, project.value.ref, source, compacted, expanded, subject)
        evaluate((project, id) => IO.raiseError(ResourceAlreadyExists(id, project)))(
          Initial,
          command
        ).rejected shouldEqual ResourceAlreadyExists(command.id, command.project)
      }

      "reject with SchemaNotFound" in {
        val list = List(
          Initial -> UpdateSchema(myId, project.value.ref, source, compacted, expanded, 1L, subject),
          Initial -> TagSchema(myId, project.value.ref, 1L, TagLabel.unsafe("myTag"), 1L, subject),
          Initial -> DeprecateSchema(myId, project.value.ref, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[SchemaNotFound]
        }
      }

      "reject with SchemaIsDeprecated" in {
        val current = SchemaGen.currentState(schema, deprecated = true)
        val list    = List(
          current -> UpdateSchema(myId, project.value.ref, source, compacted, expanded, 1L, subject),
          current -> TagSchema(myId, project.value.ref, 1L, TagLabel.unsafe("a"), 1L, subject),
          current -> DeprecateSchema(myId, project.value.ref, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[SchemaIsDeprecated]
        }
      }

      "reject with RevisionNotFound" in {
        eval(
          SchemaGen.currentState(schema),
          TagSchema(myId, project.value.ref, 3L, TagLabel.unsafe("myTag"), 1L, subject)
        ).rejected shouldEqual RevisionNotFound(provided = 3L, current = 1L)
      }

      "reject with ReservedSchemaId" in {
        val reserved = schemas + "myid"

        eval(
          Initial,
          CreateSchema(reserved, project.value.ref, source, compacted, expanded, subject)
        ).rejected shouldEqual ReservedSchemaId(reserved)
      }

    }

    "producing next state" should {
      val tags    = Map(TagLabel.unsafe("a") -> 1L)
      val current = SchemaGen.currentState(schema.copy(tags = tags))

      "create a new SchemaCreated state" in {
        next(
          Initial,
          SchemaCreated(myId, project.value.ref, source, compacted, expanded, 1L, epoch, subject)
        ) shouldEqual
          current.copy(createdAt = epoch, createdBy = subject, updatedAt = epoch, updatedBy = subject, tags = Map.empty)

        next(
          current,
          SchemaCreated(myId, project.value.ref, source, compacted, expanded, 1L, time2, subject)
        ) shouldEqual current
      }

      "create a new SchemaUpdated state" in {
        next(
          Initial,
          SchemaUpdated(myId, project.value.ref, source, compacted, expanded, 1L, time2, subject)
        ) shouldEqual Initial

        next(
          current,
          SchemaUpdated(myId, project.value.ref, sourceUpdated, compacted, expanded, 2L, time2, subject)
        ) shouldEqual
          current.copy(rev = 2L, source = sourceUpdated, updatedAt = time2, updatedBy = subject)
      }

      "create new SchemaTagAdded state" in {
        val tag = TagLabel.unsafe("tag")
        next(
          Initial,
          SchemaTagAdded(myId, project.value.ref, 1L, tag, 2L, time2, subject)
        ) shouldEqual Initial

        next(current, SchemaTagAdded(myId, project.value.ref, 1L, tag, 2L, time2, subject)) shouldEqual
          current.copy(rev = 2L, updatedAt = time2, updatedBy = subject, tags = tags + (tag -> 1L))
      }

      "create new SchemaDeprecated state" in {
        next(Initial, SchemaDeprecated(myId, project.value.ref, 1L, time2, subject)) shouldEqual Initial

        next(current, SchemaDeprecated(myId, project.value.ref, 2L, time2, subject)) shouldEqual
          current.copy(rev = 2L, deprecated = true, updatedAt = time2, updatedBy = subject)
      }
    }
  }
}
