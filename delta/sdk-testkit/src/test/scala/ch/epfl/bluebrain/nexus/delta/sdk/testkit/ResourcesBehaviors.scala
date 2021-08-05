package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.{NoOffset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResolution.{FetchResource, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen, ResourceResolutionGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.OrganizationNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.{ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResolverResolutionRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent.{ResourceCreated, ResourceDeprecated, ResourceTagAdded, ResourceUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues, TestHelpers}
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

trait ResourcesBehaviors {
  this: AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with OptionValues
    with Inspectors
    with CancelAfterFailure
    with CirceLiteral =>

  val epoch: Instant            = Instant.EPOCH
  implicit val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit val caller: Caller   = Caller(subject, Set(subject))

  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  val uuid                  = UUID.randomUUID()
  implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

  implicit def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.metadata        -> jsonContentOf("contexts/metadata.json").topContextValueOrEmpty,
      contexts.shacl           -> jsonContentOf("contexts/shacl.json").topContextValueOrEmpty,
      contexts.schemasMetadata -> jsonContentOf("contexts/schemas-metadata.json").topContextValueOrEmpty
    )

  val org               = Label.unsafe("myorg")
  val am                = ApiMappings(Map("nxv" -> nxv.base, "Person" -> schema.Person))
  val projBase          = nxv.base
  val project           = ProjectGen.project("myorg", "myproject", base = projBase, mappings = am)
  val projectDeprecated = ProjectGen.project("myorg", "myproject2")
  val projectRef        = project.ref
  val allApiMappings    = am + Resources.mappings

  val schemaSource = jsonContentOf("resources/schema.json").addContext(contexts.shacl, contexts.schemasMetadata)
  val schema1      = SchemaGen.schema(nxv + "myschema", project.ref, schemaSource.removeKeys(keywords.id))
  val schema2      = SchemaGen.schema(schema.Person, project.ref, schemaSource.removeKeys(keywords.id))

  val resolverContextResolution: ResolverContextResolution = new ResolverContextResolution(
    res,
    (r, p, _) => resources.fetch(r, p).bimap(_ => ResourceResolutionReport(), _.value)
  )

  lazy val projectSetup: UIO[(OrganizationsDummy, ProjectsDummy)] = ProjectSetup.init(
    orgsToCreate = org :: Nil,
    projectsToCreate = project :: projectDeprecated :: Nil,
    projectsToDeprecate = projectDeprecated.ref :: Nil
  )

  private val fetchSchema: (ResourceRef, ProjectRef) => FetchResource[Schema] = {
    case (ref, _) if ref.iri == schema2.id => UIO.some(SchemaGen.resourceFor(schema2, deprecated = true))
    case (ref, _) if ref.iri == schema1.id => UIO.some(SchemaGen.resourceFor(schema1))
    case _                                 => UIO.none
  }

  val resourceResolution: ResourceResolution[Schema] = ResourceResolutionGen.singleInProject(projectRef, fetchSchema)

  def create: UIO[Resources]

  lazy val resources: Resources = create.accepted

  "The Resources operations bundle" when {

    // format: off
    val myId  = nxv + "myid"  // Resource created against the resource schema with id present on the payload
    val myId3 = nxv + "myid3" // Resource created against the resource schema with id present on the payload and passed explicitly
    val myId4 = nxv + "myid4" // Resource created against schema1 with id present on the payload and passed explicitly
    val myId5 = nxv + "myid5" // Resource created against the resource schema with id passed explicitly but not present on the payload
    val myId6 = nxv + "myid6" // Resource created against schema1 with id passed explicitly but not present on the payload
    val myId7 = nxv + "myid7" // Resource created against the resource schema with id passed explicitly and with payload without @context
    val myId8  = nxv + "myid8" // Resource created against the resource schema with id present on the payload and having its context pointing on metadata and myId1 and myId2
    val myId9  = nxv + "myid9" // Resource created against the resource schema with id present on the payload and having its context pointing on metadata and myId8 so therefore myId1 and myId2

    // format: on
    val resourceSchema = Latest(schemas.resources)
    val myId2          = nxv + "myid2" // Resource created against the schema1 with id present on the payload
    val types          = Set(nxv + "Custom")
    val source         = jsonContentOf("resources/resource.json", "id" -> myId)
    val tag            = TagLabel.unsafe("tag")

    "creating a resource" should {
      "succeed with the id present on the payload" in {
        forAll(List(myId -> resourceSchema, myId2 -> Latest(schema1.id))) { case (id, schemaRef) =>
          val sourceWithId = source deepMerge json"""{"@id": "$id"}"""
          val expectedData = ResourceGen.resource(id, projectRef, sourceWithId, Revision(schemaRef.iri, 1))
          val resource     = resources.create(projectRef, schemaRef, sourceWithId).accepted
          resource shouldEqual ResourceGen.resourceFor(
            expectedData,
            types = types,
            subject = subject,
            am = allApiMappings,
            base = projBase
          )
        }
      }

      "succeed with the id present on the payload and passed" in {
        val list =
          List(
            (myId3, "_", resourceSchema),
            (myId4, "myschema", Latest(schema1.id))
          )
        forAll(list) { case (id, schemaSegment, schemaRef) =>
          val sourceWithId = source deepMerge json"""{"@id": "$id"}"""
          val expectedData = ResourceGen.resource(id, projectRef, sourceWithId, Revision(schemaRef.iri, 1))
          val resource     = resources.create(id, projectRef, schemaSegment, sourceWithId).accepted
          resource shouldEqual ResourceGen.resourceFor(
            expectedData,
            types = types,
            subject = subject,
            am = allApiMappings,
            base = projBase
          )
        }
      }

      "succeed with the passed id" in {
        val list = List(
          ("nxv:myid5", myId5, resourceSchema),
          ("nxv:myid6", myId6, Latest(schema1.id))
        )
        forAll(list) { case (segment, iri, schemaRef) =>
          val sourceWithId    = source deepMerge json"""{"@id": "$iri"}"""
          val sourceWithoutId = source.removeKeys(keywords.id)
          val expectedData    =
            ResourceGen
              .resource(iri, projectRef, sourceWithId, Revision(schemaRef.iri, 1))
              .copy(source = sourceWithoutId)
          val resource        = resources.create(segment, projectRef, schemaRef, sourceWithoutId).accepted
          resource shouldEqual ResourceGen.resourceFor(
            expectedData,
            types = types,
            subject = subject,
            am = allApiMappings,
            base = projBase
          )
        }
      }

      "succeed with payload without @context" in {
        val payload        = json"""{"name": "Alice"}"""
        val payloadWithCtx =
          payload.addContext(json"""{"@context": {"@vocab": "${nxv.base}","@base": "${nxv.base}"}}""")
        val schemaRev      = Revision(resourceSchema.iri, 1)
        val expectedData   =
          ResourceGen.resource(myId7, projectRef, payloadWithCtx, schemaRev).copy(source = payload)

        resources.create(myId7, projectRef, schemas.resources, payload).accepted shouldEqual
          ResourceGen.resourceFor(expectedData, subject = subject, am = allApiMappings, base = projBase)
      }

      "succeed with the id present on the payload and pointing to another resource in its context" in {
        val sourceMyId8  =
          source.addContext(contexts.metadata).addContext(myId).addContext(myId2) deepMerge json"""{"@id": "$myId8"}"""
        val schemaRev    = Revision(resourceSchema.iri, 1)
        val expectedData =
          ResourceGen.resource(myId8, projectRef, sourceMyId8, schemaRev)(resolverContextResolution(projectRef))
        val resource     = resources.create(projectRef, resourceSchema, sourceMyId8).accepted
        resource shouldEqual ResourceGen.resourceFor(
          expectedData,
          types = types,
          subject = subject,
          am = allApiMappings,
          base = projBase
        )
      }

      "succeed when pointing to another resource which itself points to other resources in its context" in {
        val sourceMyId9  = source.addContext(contexts.metadata).addContext(myId8) deepMerge json"""{"@id": "$myId9"}"""
        val schemaRev    = Revision(resourceSchema.iri, 1)
        val expectedData =
          ResourceGen.resource(myId9, projectRef, sourceMyId9, schemaRev)(resolverContextResolution(projectRef))
        val resource     = resources.create(projectRef, resourceSchema, sourceMyId9).accepted
        resource shouldEqual ResourceGen.resourceFor(
          expectedData,
          types = types,
          subject = subject,
          am = allApiMappings,
          base = projBase
        )
      }

      "reject with different ids on the payload and passed" in {
        val otherId = nxv + "other"
        resources.create(otherId, projectRef, schemas.resources, source).rejected shouldEqual
          UnexpectedResourceId(id = otherId, payloadId = myId)
      }

      "reject if it already exists" in {
        resources.create(myId, projectRef, schemas.resources, source).rejected shouldEqual
          ResourceAlreadyExists(myId, projectRef)

        resources
          .create("nxv:myid", projectRef, schemas.resources, source)
          .rejected shouldEqual
          ResourceAlreadyExists(myId, projectRef)
      }

      "reject if it does not validate against its schema" in {
        val otherId     = nxv + "other"
        val wrongSource = source deepMerge json"""{"@id": "$otherId", "number": "wrong"}"""
        resources.create(otherId, projectRef, schema1.id, wrongSource).rejectedWith[InvalidResource]
      }

      "reject if the validated schema is deprecated" in {
        val otherId    = nxv + "other"
        val noIdSource = source.removeKeys(keywords.id)
        forAll(List[IdSegment](schema2.id, "Person")) { segment =>
          resources.create(otherId, projectRef, segment, noIdSource).rejected shouldEqual
            SchemaIsDeprecated(schema2.id)

        }
      }

      "reject if the validated schema does not exists" in {
        val otherId    = nxv + "other"
        val noIdSource = source.removeKeys(keywords.id)
        resources.create(otherId, projectRef, "nxv:notExist", noIdSource).rejected shouldEqual
          InvalidSchemaRejection(
            Latest(nxv + "notExist"),
            project.ref,
            ResourceResolutionReport(
              ResolverReport.failed(
                nxv + "in-project",
                project.ref -> ResolverResolutionRejection.ResourceNotFound(nxv + "notExist", project.ref)
              )
            )
          )
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        resources.create(projectRef, schemas.resources, source).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))

        resources.create(myId, projectRef, schemas.resources, source).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        resources.create(projectDeprecated.ref, schemas.resources, source).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(projectDeprecated.ref))

        resources
          .create(myId, projectDeprecated.ref, schemas.resources, source)
          .rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(projectDeprecated.ref))
      }

      "reject if part of the context can't be resolved" in {
        val myIdX           = nxv + "myidx"
        val unknownResource = nxv + "fail"
        val sourceMyIdX     =
          source.addContext(contexts.metadata).addContext(unknownResource) deepMerge json"""{"@id": "$myIdX"}"""
        resources.create(projectRef, resourceSchema, sourceMyIdX).rejectedWith[InvalidJsonLdFormat]
      }
    }

    "updating a resource" should {

      "succeed" in {
        val updated      = source.removeKeys(keywords.id) deepMerge json"""{"number": 60}"""
        val expectedData = ResourceGen.resource(myId2, projectRef, updated, Revision(schema1.id, 1))
        resources.update(myId2, projectRef, Some(schema1.id), 1L, updated).accepted shouldEqual
          ResourceGen.resourceFor(
            expectedData,
            types = types,
            subject = subject,
            rev = 2L,
            am = allApiMappings,
            base = projBase
          )
      }

      "succeed without specifying the schema" in {
        val updated      = source.removeKeys(keywords.id) deepMerge json"""{"number": 65}"""
        val expectedData = ResourceGen.resource(myId2, projectRef, updated, Revision(schema1.id, 1))
        resources.update("nxv:myid2", projectRef, None, 2L, updated).accepted shouldEqual
          ResourceGen.resourceFor(
            expectedData,
            types = types,
            subject = subject,
            rev = 3L,
            am = allApiMappings,
            base = projBase
          )
      }

      "reject if it doesn't exists" in {
        resources
          .update(nxv + "other", projectRef, None, 1L, json"""{"a": "b"}""")
          .rejectedWith[ResourceNotFound]
      }

      "reject if the revision passed is incorrect" in {
        resources.update(myId, projectRef, None, 3L, json"""{"a": "b"}""").rejected shouldEqual
          IncorrectRev(provided = 3L, expected = 1L)
      }

      "reject if deprecated" in {
        resources.deprecate(myId3, projectRef, None, 1L).accepted
        resources
          .update(myId3, projectRef, None, 2L, json"""{"a": "b"}""")
          .rejectedWith[ResourceIsDeprecated]
        resources
          .update("nxv:myid3", projectRef, None, 2L, json"""{"a": "b"}""")
          .rejectedWith[ResourceIsDeprecated]
      }

      "reject if schemas do not match" in {
        resources
          .update(myId2, projectRef, Some(schemas.resources), 3L, json"""{"a": "b"}""")
          .rejectedWith[UnexpectedResourceSchema]
      }

      "reject if it does not validate against its schema" in {
        val wrongSource = source.removeKeys(keywords.id) deepMerge json"""{"number": "wrong"}"""
        resources.update(myId2, projectRef, Some(schema1.id), 3L, wrongSource).rejectedWith[InvalidResource]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        resources.update(myId, projectRef, None, 2L, source).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        resources.update(myId, projectDeprecated.ref, None, 2L, source).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(projectDeprecated.ref))
      }
    }

    "tagging a resource" should {

      "succeed" in {
        val schemaRev    = Revision(resourceSchema.iri, 1)
        val expectedData = ResourceGen.resource(myId, projectRef, source, schemaRev, tags = Map(tag -> 1L))
        val resource     =
          resources.tag(myId, projectRef, Some(schemas.resources), tag, 1L, 1L).accepted
        resource shouldEqual
          ResourceGen.resourceFor(
            expectedData,
            types = types,
            subject = subject,
            rev = 2L,
            am = allApiMappings,
            base = projBase
          )
      }

      "reject if it doesn't exists" in {
        resources.tag(nxv + "other", projectRef, None, tag, 1L, 1L).rejectedWith[ResourceNotFound]
      }

      "reject if the revision passed is incorrect" in {
        resources.tag(myId, projectRef, None, tag, 1L, 3L).rejected shouldEqual
          IncorrectRev(provided = 3L, expected = 2L)
      }

      "reject if deprecated" in {
        resources.tag(myId3, projectRef, None, tag, 2L, 2L).rejectedWith[ResourceIsDeprecated]
        resources.tag("nxv:myid3", projectRef, None, tag, 2L, 2L).rejectedWith[ResourceIsDeprecated]
      }

      "reject if schemas do not match" in {
        resources
          .tag(myId2, projectRef, Some(schemas.resources), tag, 2L, 3L)
          .rejectedWith[UnexpectedResourceSchema]
      }

      "reject if tag revision not found" in {
        resources
          .tag(myId, projectRef, Some(schemas.resources), tag, 6L, 2L)
          .rejected shouldEqual
          RevisionNotFound(provided = 6L, current = 2L)
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        resources.tag(myId, projectRef, None, tag, 2L, 1L).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        resources.tag(myId, projectDeprecated.ref, None, tag, 2L, 1L).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(projectDeprecated.ref))
      }
    }

    "deprecating a resource" should {

      "succeed" in {
        val sourceWithId = source deepMerge json"""{"@id": "$myId4"}"""
        val expectedData = ResourceGen.resource(myId4, projectRef, sourceWithId, Revision(schema1.id, 1))
        val resource     = resources.deprecate(myId4, projectRef, Some(schema1.id), 1L).accepted
        resource shouldEqual
          ResourceGen.resourceFor(
            expectedData,
            types = types,
            subject = subject,
            rev = 2L,
            deprecated = true,
            am = allApiMappings,
            base = projBase
          )
      }

      "reject if it doesn't exists" in {
        resources.deprecate(nxv + "other", projectRef, None, 1L).rejectedWith[ResourceNotFound]
      }

      "reject if the revision passed is incorrect" in {
        resources.deprecate(myId, projectRef, None, 3L).rejected shouldEqual
          IncorrectRev(provided = 3L, expected = 2L)
      }

      "reject if deprecated" in {
        resources.deprecate(myId4, projectRef, None, 2L).rejectedWith[ResourceIsDeprecated]
        resources.deprecate("nxv:myid4", projectRef, None, 2L).rejectedWith[ResourceIsDeprecated]
      }

      "reject if schemas do not match" in {
        resources.deprecate(myId2, projectRef, Some(schemas.resources), 3L).rejectedWith[UnexpectedResourceSchema]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        resources.deprecate(myId, projectRef, None, 1L).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        resources.deprecate(myId, projectDeprecated.ref, None, 1L).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(projectDeprecated.ref))
      }

    }

    "fetching a resource" should {
      val schemaRev          = Revision(resourceSchema.iri, 1)
      val expectedData       = ResourceGen.resource(myId, projectRef, source, schemaRev)
      val expectedDataLatest = expectedData.copy(tags = Map(tag -> 1L))

      "succeed" in {
        forAll(List[Option[IdSegment]](None, Some(schemas.resources))) { schema =>
          resources.fetch(myId, projectRef, schema).accepted shouldEqual
            ResourceGen.resourceFor(
              expectedDataLatest,
              types = types,
              subject = subject,
              rev = 2L,
              am = allApiMappings,
              base = projBase
            )
        }
      }

      "succeed by tag" in {
        forAll(List[Option[IdSegment]](None, Some(schemas.resources))) { schema =>
          resources.fetch(IdSegmentRef("nxv:myid", tag), projectRef, schema).accepted shouldEqual
            ResourceGen.resourceFor(
              expectedData,
              types = types,
              subject = subject,
              rev = 1L,
              am = allApiMappings,
              base = projBase
            )
        }
      }

      "succeed by rev" in {
        forAll(List[Option[IdSegment]](None, Some(schemas.resources))) { schema =>
          resources.fetch(IdSegmentRef(myId, 1), projectRef, schema).accepted shouldEqual
            ResourceGen.resourceFor(
              expectedData,
              types = types,
              subject = subject,
              rev = 1L,
              am = allApiMappings,
              base = projBase
            )
        }
      }

      "reject if tag does not exist" in {
        val otherTag = TagLabel.unsafe("other")
        forAll(List[Option[IdSegment]](None, Some(schemas.resources))) { schema =>
          resources.fetch(IdSegmentRef(myId, otherTag), projectRef, schema).rejected shouldEqual TagNotFound(otherTag)
        }
      }

      "reject if revision does not exist" in {
        forAll(List[Option[IdSegment]](None, Some(schemas.resources))) { schema =>
          resources.fetch(IdSegmentRef(myId, 5), projectRef, schema).rejected shouldEqual
            RevisionNotFound(provided = 5L, current = 2L)
        }
      }

      "fail fetching if resource does not exist" in {
        val myId = nxv + "notFound"
        resources.fetch(myId, projectRef, None).rejectedWith[ResourceNotFound]
        resources.fetch(IdSegmentRef(myId, tag), projectRef, None).rejectedWith[ResourceNotFound]
        resources.fetch(IdSegmentRef(myId, 2), projectRef, None).rejectedWith[ResourceNotFound]
      }

      "fail fetching if schema is not resource schema" in {
        resources.fetch(myId, projectRef, Some(schema1.id)).rejectedWith[ResourceNotFound]
        resources.fetch(IdSegmentRef(myId, tag), projectRef, Some(schema1.id)).rejectedWith[ResourceNotFound]
        resources.fetch(IdSegmentRef(myId, 2), projectRef, Some(schema1.id)).rejectedWith[ResourceNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        resources.fetch(myId, projectRef, None).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "fail fetching if resource does not exist on deprecated project" in {
        resources.fetch(myId, projectDeprecated.ref, None).rejectedWith[ResourceNotFound]
      }
    }

    "fetching SSE" should {
      val allEvents = SSEUtils.list(
        myId  -> ResourceCreated,
        myId2 -> ResourceCreated,
        myId3 -> ResourceCreated,
        myId4 -> ResourceCreated,
        myId5 -> ResourceCreated,
        myId6 -> ResourceCreated,
        myId7 -> ResourceCreated,
        myId8 -> ResourceCreated,
        myId9 -> ResourceCreated,
        myId2 -> ResourceUpdated,
        myId2 -> ResourceUpdated,
        myId3 -> ResourceDeprecated,
        myId  -> ResourceTagAdded,
        myId4 -> ResourceDeprecated
      )

      "get the different events from start" in {
        val streams = List(
          resources.events(NoOffset),
          resources.events(org, NoOffset).accepted,
          resources.events(projectRef, NoOffset).accepted
        )
        forAll(streams) { stream =>
          val events = stream
            .map { e => (e.event.id, e.eventType, e.offset) }
            .take(allEvents.size.toLong)
            .compile
            .toList

          events.accepted shouldEqual allEvents
        }
      }

      "get the different events from offset 2" in {
        val streams = List(
          resources.events(Sequence(2L)),
          resources.events(org, Sequence(2L)).accepted,
          resources.events(projectRef, Sequence(2L)).accepted
        )
        forAll(streams) { stream =>
          val events = stream
            .map { e => (e.event.id, e.eventType, e.offset) }
            .take(allEvents.size.toLong - 2)
            .compile
            .toList

          events.accepted shouldEqual allEvents.drop(2)
        }
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        resources.events(projectRef, NoOffset).rejected shouldEqual WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if organization does not exist" in {
        val org = Label.unsafe("other")
        resources.events(org, NoOffset).rejected shouldEqual WrappedOrganizationRejection(OrganizationNotFound(org))
      }
    }
  }
}
