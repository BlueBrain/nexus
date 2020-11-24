package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Resources.{evaluate, next, FetchSchema}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection.{SchemaIsDeprecated, SchemaNotFound}
import ch.epfl.bluebrain.nexus.testkit._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class ResourcesSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValuable
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers
    with CirceLiteral
    with OptionValues {

  "The Resources state machine" when {
    implicit val sc: Scheduler = Scheduler.global
    val epoch                  = Instant.EPOCH
    val time2                  = Instant.ofEpochMilli(10L)
    val subject                = User("myuser", Label.unsafe("myrealm"))

    implicit val res: RemoteContextResolution =
      RemoteContextResolution.fixed(contexts.shacl -> jsonContentOf("contexts/shacl.json"))

    val project                               = ProjectGen.resourceFor(ProjectGen.project("myorg", "myproject", base = nxv.base))

    val schemaSource             = jsonContentOf("resources/schema.json")
    val schema1                  = SchemaGen.schema(nxv + "myschema", project.value.ref, schemaSource)
    val schema2                  = SchemaGen.schema(nxv + "myschema2", project.value.ref, schemaSource)
    val fetchSchema: FetchSchema = {
      case (pRef, _) if project.value.ref != pRef =>
        IO.raiseError(WrappedProjectRejection(ProjectNotFound(pRef)))
      case (_, ref) if ref.iri == schema2.id      =>
        IO.raiseError(WrappedSchemaRejection(SchemaIsDeprecated(ref.iri)))
      case (_, ref) if ref.iri == schema1.id      =>
        IO.pure(schema1)
      case (_, ref)                               =>
        IO.raiseError(WrappedSchemaRejection(SchemaNotFound(ref.iri, project.value.ref)))
    }

    val eval: (ResourceState, ResourceCommand) => IO[ResourceRejection, ResourceEvent] = evaluate(fetchSchema)

    val myId   = nxv + "myid"
    val types  = Set(nxv + "Custom")
    val source = jsonContentOf("resources/resource.json", "id" -> myId)

    "evaluating an incoming command" should {
      "create a new event from a CreateResource command" in {
        forAll(List(Latest(schemas.resources), Latest(schema1.id))) { schemaRef =>
          val myIdResource = ResourceGen.resource(myId, project.value.ref, source, schemaRef)
          val compacted    = myIdResource.compacted
          val expanded     = myIdResource.expanded
          eval(
            Initial,
            CreateResource(myId, project.value.ref, schemaRef, source, compacted, expanded, subject)
          ).accepted shouldEqual
            ResourceCreated(myId, project.value.ref, schemaRef, types, source, compacted, expanded, 1L, epoch, subject)
        }
      }

      "create a new event from a UpdateResource command" in {
        forAll(List(None -> Latest(schemas.resources), Some(Latest(schema1.id)) -> Latest(schema1.id))) {
          case (schemaOptCmd, schemaEvent) =>
            val current = ResourceGen.currentState(myId, project.value.ref, source, schemaEvent, types)

            val newSource = source.deepMerge(json"""{"@type": ["Custom", "Person"]}""")
            val newTypes  = Set(nxv + "Custom", nxv + "Person")
            val updated   = ResourceGen.resource(myId, project.value.ref, newSource, schemaEvent)
            val compacted = updated.compacted
            val expanded  = updated.expanded
            eval(
              current,
              UpdateResource(myId, project.value.ref, schemaOptCmd, newSource, compacted, expanded, 1L, subject)
            ).accepted shouldEqual
              ResourceUpdated(myId, project.value.ref, newTypes, newSource, compacted, expanded, 2L, epoch, subject)
        }
      }

      "create a new event from a TagResource command" in {
        val list = List(
          None                     -> Latest(schemas.resources),
          None                     -> Latest(schema1.id),
          Some(Latest(schema1.id)) -> Latest(schema1.id)
        )
        forAll(list) { case (schemaOptCmd, schemaEvent) =>
          val current =
            ResourceGen.currentState(myId, project.value.ref, source, schemaEvent, types, rev = 2L)

          eval(
            current,
            TagResource(myId, project.value.ref, schemaOptCmd, 1L, Label.unsafe("myTag"), 2L, subject)
          ).accepted shouldEqual
            ResourceTagAdded(myId, project.value.ref, types, 1L, Label.unsafe("myTag"), 3L, epoch, subject)
        }
      }

      "create a new event from a DeprecateResource command" in {
        val list = List(
          None                     -> Latest(schemas.resources),
          None                     -> Latest(schema1.id),
          Some(Latest(schema1.id)) -> Latest(schema1.id)
        )
        forAll(list) { case (schemaOptCmd, schemaEvent) =>
          val current =
            ResourceGen.currentState(myId, project.value.ref, source, schemaEvent, types, rev = 2L)

          eval(current, DeprecateResource(myId, project.value.ref, schemaOptCmd, 2, subject)).accepted shouldEqual
            ResourceDeprecated(myId, project.value.ref, types, 3L, epoch, subject)
        }
      }

      "reject with IncorrectRev" in {
        val current   = ResourceGen.currentState(myId, project.value.ref, source, Latest(schemas.resources), types)
        val compacted = current.compacted
        val expanded  = current.expanded
        val list      = List(
          current -> UpdateResource(myId, project.value.ref, None, source, compacted, expanded, 2L, subject),
          current -> TagResource(myId, project.value.ref, None, 1L, Label.unsafe("tag"), 2L, subject),
          current -> DeprecateResource(myId, project.value.ref, None, 2L, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejected shouldEqual IncorrectRev(provided = 2L, expected = 1L)
        }
      }

      "reject with InvalidResource" in {
        val current       = ResourceGen.currentState(myId, project.value.ref, source, Latest(schema1.id), types)
        val wrongSource   = source deepMerge json"""{"number": "unexpected"}"""
        val wrongResource = ResourceGen.resource(myId, project.value.ref, wrongSource)
        val compacted     = wrongResource.compacted
        val expanded      = wrongResource.expanded
        val schema        = Latest(schema1.id)
        val list          = List(
          Initial -> CreateResource(myId, project.value.ref, schema, wrongSource, compacted, expanded, subject),
          current -> UpdateResource(myId, project.value.ref, None, wrongSource, compacted, expanded, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[InvalidResource]
        }
      }

      "reject with SchemaIsDeprecated" in {
        val schema    = Latest(schema2.id)
        val current   = ResourceGen.currentState(myId, project.value.ref, source, schema, types)
        val compacted = current.compacted
        val expanded  = current.expanded
        val list      = List(
          Initial -> CreateResource(myId, project.value.ref, schema, source, compacted, expanded, subject),
          current -> UpdateResource(myId, project.value.ref, None, source, compacted, expanded, 1L, subject),
          current -> UpdateResource(myId, project.value.ref, Some(schema), source, compacted, expanded, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejected shouldEqual WrappedSchemaRejection(SchemaIsDeprecated(schema2.id))
        }
      }

      "reject with SchemaNotFound" in {
        val notFoundSchema = Latest(nxv + "notFound")
        val current        = ResourceGen.currentState(myId, project.value.ref, source, Latest(schema1.id), types)
        val compacted      = current.compacted
        val expanded       = current.expanded
        eval(
          Initial,
          CreateResource(myId, project.value.ref, notFoundSchema, source, compacted, expanded, subject)
        ).rejected shouldEqual WrappedSchemaRejection(SchemaNotFound(notFoundSchema.iri, project.value.ref))
      }

      "reject with ResourceSchemaUnexpected" in {
        val current     = ResourceGen.currentState(myId, project.value.ref, source, Latest(schema1.id), types)
        val otherSchema = Some(Latest(schema2.id))
        val compacted   = current.compacted
        val expanded    = current.expanded
        eval(
          current,
          UpdateResource(myId, project.value.ref, otherSchema, source, compacted, expanded, 1L, subject)
        ).rejected shouldEqual
          UnexpectedResourceSchema(myId, provided = otherSchema.value, expected = Latest(schema1.id))
      }

      "reject with ResourceAlreadyExists" in {
        val current   = ResourceGen.currentState(myId, project.value.ref, source, Latest(schema1.id), types)
        val compacted = current.compacted
        val expanded  = current.expanded
        eval(current, CreateResource(myId, project.value.ref, Latest(schema1.id), source, compacted, expanded, subject))
          .rejectedWith[ResourceAlreadyExists]
      }

      "reject with ResourceNotFound" in {
        val current   = ResourceGen.currentState(myId, project.value.ref, source, Latest(schemas.resources), types)
        val compacted = current.compacted
        val expanded  = current.expanded
        val list      = List(
          Initial -> UpdateResource(myId, project.value.ref, None, source, compacted, expanded, 1L, subject),
          Initial -> TagResource(myId, project.value.ref, None, 1L, Label.unsafe("myTag"), 1L, subject),
          Initial -> DeprecateResource(myId, project.value.ref, None, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[ResourceNotFound]
        }
      }

      "reject with ResourceIsDeprecated" in {
        val current   =
          ResourceGen.currentState(myId, project.value.ref, source, Latest(schemas.resources), types, deprecated = true)
        val compacted = current.compacted
        val expanded  = current.expanded
        val list      = List(
          current -> UpdateResource(myId, project.value.ref, None, source, compacted, expanded, 1L, subject),
          current -> TagResource(myId, project.value.ref, None, 1L, Label.unsafe("a"), 1L, subject),
          current -> DeprecateResource(myId, project.value.ref, None, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[ResourceIsDeprecated]
        }
      }

      "reject with RevisionNotFound" in {
        val current = ResourceGen.currentState(myId, project.value.ref, source, Latest(schemas.resources), types)
        eval(
          current,
          TagResource(myId, project.value.ref, None, 3L, Label.unsafe("myTag"), 1L, subject)
        ).rejected shouldEqual
          RevisionNotFound(provided = 3L, current = 1L)
      }

    }

    "producing next state" should {
      val schema    = Latest(schemas.resources)
      val tags      = Map(Label.unsafe("a") -> 1L)
      val current   = ResourceGen.currentState(myId, project.value.ref, source, schema, types, tags)
      val compacted = current.compacted
      val expanded  = current.expanded

      "create a new ResourceCreated state" in {
        next(
          Initial,
          ResourceCreated(myId, project.value.ref, schema, types, source, compacted, expanded, 1L, epoch, subject)
        ) shouldEqual
          current.copy(createdAt = epoch, createdBy = subject, updatedAt = epoch, updatedBy = subject, tags = Map.empty)

        next(
          current,
          ResourceCreated(myId, project.value.ref, schema, types, source, compacted, expanded, 1L, time2, subject)
        ) shouldEqual current
      }

      "create a new ResourceUpdated state" in {
        val newTypes  = types + (nxv + "Other")
        val newSource = source deepMerge json"""{"key": "value"}"""
        next(
          Initial,
          ResourceUpdated(myId, project.value.ref, newTypes, source, compacted, expanded, 1L, time2, subject)
        ) shouldEqual
          Initial

        next(
          current,
          ResourceUpdated(myId, project.value.ref, newTypes, newSource, compacted, expanded, 2L, time2, subject)
        ) shouldEqual
          current.copy(
            rev = 2L,
            source = newSource,
            updatedAt = time2,
            updatedBy = subject,
            types = newTypes
          )
      }

      "create new ResourceTagAdded state" in {
        val tag = Label.unsafe("tag")
        next(
          Initial,
          ResourceTagAdded(myId, project.value.ref, types, 1L, tag, 2L, time2, subject)
        ) shouldEqual Initial

        next(current, ResourceTagAdded(myId, project.value.ref, types, 1L, tag, 2L, time2, subject)) shouldEqual
          current.copy(rev = 2L, updatedAt = time2, updatedBy = subject, tags = tags + (tag -> 1L))
      }

      "create new ResourceDeprecated state" in {
        next(Initial, ResourceDeprecated(myId, project.value.ref, types, 1L, time2, subject)) shouldEqual Initial

        next(current, ResourceDeprecated(myId, project.value.ref, types, 2L, time2, subject)) shouldEqual
          current.copy(rev = 2L, deprecated = true, updatedAt = time2, updatedBy = subject)
      }
    }
  }
}
