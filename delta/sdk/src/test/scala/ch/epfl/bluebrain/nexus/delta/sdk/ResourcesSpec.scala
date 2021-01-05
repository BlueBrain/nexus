package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceResolution.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.Resources.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen, ResourceResolutionGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverResolutionRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.testkit._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant

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
    val caller                 = Caller(subject, Set.empty)

    implicit val res: RemoteContextResolution =
      RemoteContextResolution.fixed(contexts.shacl -> jsonContentOf("contexts/shacl.json"))

    val project                               = ProjectGen.resourceFor(ProjectGen.project("myorg", "myproject", base = nxv.base))
    val projectRef                            = project.value.ref

    val schemaSource = jsonContentOf("resources/schema.json")
    val schema1      = SchemaGen.schema(nxv + "myschema", projectRef, schemaSource)
    val schema2      = SchemaGen.schema(nxv + "myschema2", projectRef, schemaSource)

    val fetchSchema: (ResourceRef, ProjectRef) => FetchResource[Schema] = {
      case (ref, _) if ref.iri == schema2.id =>
        IO.pure(SchemaGen.resourceFor(schema2, deprecated = true))
      case (ref, _) if ref.iri == schema1.id =>
        IO.pure(SchemaGen.resourceFor(schema1))
      case (ref, pRef)                       =>
        IO.raiseError(ResolverResolutionRejection.ResourceNotFound(ref.iri, pRef))
    }

    val resourceResolution = ResourceResolutionGen.singleInProject(projectRef, fetchSchema)

    val eval: (ResourceState, ResourceCommand) => IO[ResourceRejection, ResourceEvent] = evaluate(resourceResolution)

    val myId   = nxv + "myid"
    val types  = Set(nxv + "Custom")
    val source = jsonContentOf("resources/resource.json", "id" -> myId)

    "evaluating an incoming command" should {
      "create a new event from a CreateResource command" in {
        forAll(List(Latest(schemas.resources), Latest(schema1.id))) { schemaRef =>
          val schemaRev    = Revision(schemaRef.iri, 1)
          val myIdResource = ResourceGen.resource(myId, projectRef, source, schemaRef)
          val comp         = myIdResource.compacted
          val exp          = myIdResource.expanded
          eval(
            Initial,
            CreateResource(myId, projectRef, schemaRef, source, comp, exp, caller)
          ).accepted shouldEqual
            ResourceCreated(myId, projectRef, schemaRev, projectRef, types, source, comp, exp, 1, epoch, subject)
        }
      }

      "create a new event from a UpdateResource command" in {
        forAll(List(None -> Latest(schemas.resources), Some(Latest(schema1.id)) -> Latest(schema1.id))) {
          case (schemaOptCmd, schemaEvent) =>
            val current   = ResourceGen.currentState(myId, projectRef, source, schemaEvent, types)
            val schemaRev = Revision(schemaEvent.iri, 1)

            val newSource = source.deepMerge(json"""{"@type": ["Custom", "Person"]}""")
            val newTpe    = Set(nxv + "Custom", nxv + "Person")
            val updated   = ResourceGen.resource(myId, projectRef, newSource, schemaEvent)
            val comp      = updated.compacted
            val exp       = updated.expanded
            eval(
              current,
              UpdateResource(myId, projectRef, schemaOptCmd, newSource, comp, exp, 1L, caller)
            ).accepted shouldEqual
              ResourceUpdated(myId, projectRef, schemaRev, projectRef, newTpe, newSource, comp, exp, 2L, epoch, subject)
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
            ResourceGen.currentState(myId, projectRef, source, schemaEvent, types, rev = 2L)

          eval(
            current,
            TagResource(myId, projectRef, schemaOptCmd, 1L, TagLabel.unsafe("myTag"), 2L, subject)
          ).accepted shouldEqual
            ResourceTagAdded(myId, projectRef, types, 1L, TagLabel.unsafe("myTag"), 3L, epoch, subject)
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
            ResourceGen.currentState(myId, projectRef, source, schemaEvent, types, rev = 2L)

          eval(current, DeprecateResource(myId, projectRef, schemaOptCmd, 2, subject)).accepted shouldEqual
            ResourceDeprecated(myId, projectRef, types, 3L, epoch, subject)
        }
      }

      "reject with IncorrectRev" in {
        val current   = ResourceGen.currentState(myId, projectRef, source, Latest(schemas.resources), types)
        val compacted = current.compacted
        val expanded  = current.expanded
        val list      = List(
          current -> UpdateResource(myId, projectRef, None, source, compacted, expanded, 2L, caller),
          current -> TagResource(myId, projectRef, None, 1L, TagLabel.unsafe("tag"), 2L, subject),
          current -> DeprecateResource(myId, projectRef, None, 2L, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejected shouldEqual IncorrectRev(provided = 2L, expected = 1L)
        }
      }

      "reject with InvalidResource" in {
        val current       = ResourceGen.currentState(myId, projectRef, source, Latest(schema1.id), types)
        val wrongSource   = source deepMerge json"""{"number": "unexpected"}"""
        val wrongResource = ResourceGen.resource(myId, projectRef, wrongSource)
        val compacted     = wrongResource.compacted
        val expanded      = wrongResource.expanded
        val schema        = Latest(schema1.id)
        val list          = List(
          Initial -> CreateResource(myId, projectRef, schema, wrongSource, compacted, expanded, caller),
          current -> UpdateResource(myId, projectRef, None, wrongSource, compacted, expanded, 1L, caller)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[InvalidResource]
        }
      }

      "reject with SchemaIsDeprecated" in {
        val schema    = Latest(schema2.id)
        val current   = ResourceGen.currentState(myId, projectRef, source, schema, types)
        val compacted = current.compacted
        val expanded  = current.expanded
        val list      = List(
          Initial -> CreateResource(myId, projectRef, schema, source, compacted, expanded, caller),
          current -> UpdateResource(myId, projectRef, None, source, compacted, expanded, 1L, caller),
          current -> UpdateResource(myId, projectRef, Some(schema), source, compacted, expanded, 1L, caller)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejected shouldEqual SchemaIsDeprecated(schema2.id)
        }
      }

      "reject with InvalidSchemaRejection" in {
        val notFoundSchema = Latest(nxv + "notFound")
        val current        = ResourceGen.currentState(myId, projectRef, source, Latest(schema1.id), types)
        val compacted      = current.compacted
        val expanded       = current.expanded
        eval(
          Initial,
          CreateResource(myId, projectRef, notFoundSchema, source, compacted, expanded, caller)
        ).rejected shouldEqual InvalidSchemaRejection(
          notFoundSchema,
          projectRef,
          ResourceResolutionReport(
            ResolverReport.failed(
              nxv + "in-project",
              projectRef -> ResolverResolutionRejection.ResourceNotFound(notFoundSchema.iri, projectRef)
            )
          )
        )
      }

      "reject with ResourceSchemaUnexpected" in {
        val current     = ResourceGen.currentState(myId, projectRef, source, Latest(schema1.id), types)
        val otherSchema = Some(Latest(schema2.id))
        val compacted   = current.compacted
        val expanded    = current.expanded
        eval(
          current,
          UpdateResource(myId, projectRef, otherSchema, source, compacted, expanded, 1L, caller)
        ).rejected shouldEqual
          UnexpectedResourceSchema(myId, provided = otherSchema.value, expected = Latest(schema1.id))
      }

      "reject with ResourceAlreadyExists" in {
        val current   = ResourceGen.currentState(myId, projectRef, source, Latest(schema1.id), types)
        val compacted = current.compacted
        val expanded  = current.expanded
        eval(current, CreateResource(myId, projectRef, Latest(schema1.id), source, compacted, expanded, caller))
          .rejectedWith[ResourceAlreadyExists]
      }

      "reject with ResourceNotFound" in {
        val current   = ResourceGen.currentState(myId, projectRef, source, Latest(schemas.resources), types)
        val compacted = current.compacted
        val expanded  = current.expanded
        val list      = List(
          Initial -> UpdateResource(myId, projectRef, None, source, compacted, expanded, 1L, caller),
          Initial -> TagResource(myId, projectRef, None, 1L, TagLabel.unsafe("myTag"), 1L, subject),
          Initial -> DeprecateResource(myId, projectRef, None, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[ResourceNotFound]
        }
      }

      "reject with ResourceIsDeprecated" in {
        val current   =
          ResourceGen.currentState(myId, projectRef, source, Latest(schemas.resources), types, deprecated = true)
        val compacted = current.compacted
        val expanded  = current.expanded
        val list      = List(
          current -> UpdateResource(myId, projectRef, None, source, compacted, expanded, 1L, caller),
          current -> TagResource(myId, projectRef, None, 1L, TagLabel.unsafe("a"), 1L, subject),
          current -> DeprecateResource(myId, projectRef, None, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[ResourceIsDeprecated]
        }
      }

      "reject with RevisionNotFound" in {
        val current = ResourceGen.currentState(myId, projectRef, source, Latest(schemas.resources), types)
        eval(
          current,
          TagResource(myId, projectRef, None, 3L, TagLabel.unsafe("myTag"), 1L, subject)
        ).rejected shouldEqual
          RevisionNotFound(provided = 3L, current = 1L)
      }

    }

    "producing next state" should {
      val schema    = Latest(schemas.resources)
      val schemaRev = Revision(schemas.resources, 1)
      val tags      = Map(TagLabel.unsafe("a") -> 1L)
      val current   = ResourceGen.currentState(myId, projectRef, source, schema, types, tags)
      val comp      = current.compacted
      val exp       = current.expanded

      "create a new ResourceCreated state" in {
        next(
          Initial,
          ResourceCreated(myId, projectRef, schemaRev, projectRef, types, source, comp, exp, 1L, epoch, subject)
        ) shouldEqual
          current.copy(
            createdAt = epoch,
            schema = schemaRev,
            createdBy = subject,
            updatedAt = epoch,
            updatedBy = subject,
            tags = Map.empty
          )

        next(
          current,
          ResourceCreated(myId, projectRef, schemaRev, projectRef, types, source, comp, exp, 1L, time2, subject)
        ) shouldEqual current
      }

      "create a new ResourceUpdated state" in {
        val newTypes  = types + (nxv + "Other")
        val newSource = source deepMerge json"""{"key": "value"}"""
        next(
          Initial,
          ResourceUpdated(myId, projectRef, schemaRev, projectRef, newTypes, source, comp, exp, 1L, time2, subject)
        ) shouldEqual
          Initial

        next(
          current,
          ResourceUpdated(myId, projectRef, schemaRev, projectRef, newTypes, newSource, comp, exp, 2L, time2, subject)
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
        val tag = TagLabel.unsafe("tag")
        next(
          Initial,
          ResourceTagAdded(myId, projectRef, types, 1L, tag, 2L, time2, subject)
        ) shouldEqual Initial

        next(current, ResourceTagAdded(myId, projectRef, types, 1L, tag, 2L, time2, subject)) shouldEqual
          current.copy(rev = 2L, updatedAt = time2, updatedBy = subject, tags = tags + (tag -> 1L))
      }

      "create new ResourceDeprecated state" in {
        next(Initial, ResourceDeprecated(myId, projectRef, types, 1L, time2, subject)) shouldEqual Initial

        next(current, ResourceDeprecated(myId, projectRef, types, 2L, time2, subject)) shouldEqual
          current.copy(rev = 2L, deprecated = true, updatedAt = time2, updatedBy = subject)
      }
    }
  }
}
