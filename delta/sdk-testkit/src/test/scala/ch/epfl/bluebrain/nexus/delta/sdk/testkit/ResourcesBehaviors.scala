package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.time.Instant
import java.util.UUID

import akka.persistence.query.{NoOffset, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.OrganizationNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.{ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent.{ResourceCreated, ResourceDeprecated, ResourceTagAdded, ResourceUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.{Resources, SchemaResource}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues, TestHelpers}
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

trait ResourcesBehaviors {
  this: AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with OptionValues
    with Inspectors
    with CirceLiteral =>

  val epoch: Instant            = Instant.EPOCH
  implicit val subject: Subject = Identity.User("user", Label.unsafe("realm"))

  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  val uuid                  = UUID.randomUUID()
  implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

  val shaclResolvedCtx                      = jsonContentOf("contexts/shacl.json")
  implicit def res: RemoteContextResolution =
    RemoteContextResolution.fixed(contexts.shacl -> shaclResolvedCtx)

  val org                                   = Label.unsafe("myorg")
  val am                                    = ApiMappings(Map("nxv" -> nxv.base, "Person" -> schema.Person))
  val projBase                              = nxv.base
  val project                               = ProjectGen.resourceFor(ProjectGen.project("myorg", "myproject", base = projBase, mappings = am))
  val projectDeprecated                     = ProjectGen.resourceFor(ProjectGen.project("myorg", "myproject2"))
  val projectRef                            = project.value.ref

  val schemaSource = jsonContentOf("resources/schema.json")
  val schema1      = SchemaGen.schema(nxv + "myschema", project.value.ref, schemaSource)
  val schema2      = SchemaGen.schema(schema.Person, project.value.ref, schemaSource)

  def fetchSchema: ResourceRef => UIO[Option[SchemaResource]] = {
    case ref if ref.iri == schema1.id => UIO.pure(Some(SchemaGen.resourceFor(schema1)))
    case ref if ref.iri == schema2.id => UIO.pure(Some(SchemaGen.resourceFor(schema2, deprecated = true)))
    case _                            => UIO.pure(None)
  }

  lazy val projectSetup: UIO[(OrganizationsDummy, ProjectsDummy)] = ProjectSetup.init(
    orgsToCreate = org :: Nil,
    projectsToCreate = project.value :: projectDeprecated.value :: Nil,
    projectsToDeprecate = projectDeprecated.value.ref :: Nil
  )

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
    // format: on
    val resourceSchema = Latest(schemas.resources)
    val myId2          = nxv + "myid2" // Resource created against the schema1 with id present on the payload
    val types          = Set(nxv + "Custom")
    val source         = jsonContentOf("resources/resource.json", "id" -> myId)
    val tag            = Label.unsafe("tag")

    "creating a resource" should {
      "succeed with the id present on the payload" in {
        forAll(List(myId -> resourceSchema, myId2 -> Latest(schema1.id))) { case (id, schemaRef) =>
          val sourceWithId = source deepMerge json"""{"@id": "$id"}"""
          val expectedData = ResourceGen.resource(id, projectRef, sourceWithId, schemaRef)
          val resource     = resources.create(projectRef, IriSegment(schemaRef.original), sourceWithId).accepted
          resource shouldEqual ResourceGen.resourceFor(
            expectedData,
            types = types,
            subject = subject,
            am = am,
            base = projBase
          )
        }
      }

      "succeed with the id present on the payload and passed" in {
        val list =
          List(
            (myId3, StringSegment("_"), resourceSchema),
            (myId4, StringSegment("myschema"), Latest(schema1.id))
          )
        forAll(list) { case (id, schemaSegment, schemaRef) =>
          val sourceWithId = source deepMerge json"""{"@id": "$id"}"""
          val expectedData = ResourceGen.resource(id, projectRef, sourceWithId, schemaRef)
          val resource     = resources.create(IriSegment(id), projectRef, schemaSegment, sourceWithId).accepted
          resource shouldEqual ResourceGen.resourceFor(
            expectedData,
            types = types,
            subject = subject,
            am = am,
            base = projBase
          )
        }
      }

      "succeed with the passed id" in {
        val list = List(
          (StringSegment("nxv:myid5"), myId5, resourceSchema),
          (StringSegment("nxv:myid6"), myId6, Latest(schema1.id))
        )
        forAll(list) { case (segment, iri, schemaRef) =>
          val sourceWithId    = source deepMerge json"""{"@id": "$iri"}"""
          val sourceWithoutId = source.removeKeys(keywords.id)
          val expectedData    =
            ResourceGen.resource(iri, projectRef, sourceWithId, schemaRef).copy(source = sourceWithoutId)
          val resource        = resources.create(segment, projectRef, IriSegment(schemaRef.original), sourceWithoutId).accepted
          resource shouldEqual ResourceGen.resourceFor(
            expectedData,
            types = types,
            subject = subject,
            am = am,
            base = projBase
          )
        }
      }

      "succeed with payload without @context" in {
        val payload        = json"""{"name": "Alice"}"""
        val payloadWithCtx =
          payload.addContext(json"""{"@context": {"@vocab": "${nxv.base}","@base": "${nxv.base}"}}""")
        val expectedData   =
          ResourceGen.resource(myId7, projectRef, payloadWithCtx, resourceSchema).copy(source = payload)

        resources.create(IriSegment(myId7), projectRef, IriSegment(schemas.resources), payload).accepted shouldEqual
          ResourceGen.resourceFor(expectedData, subject = subject, am = am, base = projBase)
      }

      "reject with different ids on the payload and passed" in {
        val otherId = nxv + "other"
        resources.create(IriSegment(otherId), projectRef, IriSegment(schemas.resources), source).rejected shouldEqual
          UnexpectedResourceId(id = otherId, payloadId = myId)
      }

      "reject if it already exists" in {
        resources.create(IriSegment(myId), projectRef, IriSegment(schemas.resources), source).rejected shouldEqual
          ResourceAlreadyExists(myId)

        resources
          .create(StringSegment("nxv:myid"), projectRef, IriSegment(schemas.resources), source)
          .rejected shouldEqual
          ResourceAlreadyExists(myId)
      }

      "reject if it does not validate against its schema" in {
        val otherId       = nxv + "other"
        val wrongSource   = source deepMerge json"""{"@id": "$otherId", "number": "wrong"}"""
        val schemaSegment = IriSegment(schema1.id)
        resources.create(IriSegment(otherId), projectRef, schemaSegment, wrongSource).rejectedWith[InvalidResource]
      }

      "reject if the validated schema is deprecated" in {
        val otherId    = nxv + "other"
        val noIdSource = source.removeKeys(keywords.id)
        forAll(List(IriSegment(schema2.id), StringSegment("Person"))) { segment =>
          resources.create(IriSegment(otherId), projectRef, segment, noIdSource).rejectedWith[SchemaIsDeprecated]

        }
      }

      "reject if the validated schema does not exists" in {
        val otherId       = nxv + "other"
        val schemaSegment = StringSegment("nxv:notExist")
        val noIdSource    = source.removeKeys(keywords.id)
        resources.create(IriSegment(otherId), projectRef, schemaSegment, noIdSource).rejectedWith[SchemaNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        resources.create(projectRef, IriSegment(schemas.resources), source).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))

        resources.create(IriSegment(myId), projectRef, IriSegment(schemas.resources), source).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        resources.create(projectDeprecated.value.ref, IriSegment(schemas.resources), source).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(projectDeprecated.value.ref))

        resources
          .create(IriSegment(myId), projectDeprecated.value.ref, IriSegment(schemas.resources), source)
          .rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(projectDeprecated.value.ref))
      }
    }

    "updating a resource" should {

      "succeed" in {
        val updated       = source.removeKeys(keywords.id) deepMerge json"""{"number": 60}"""
        val expectedData  = ResourceGen.resource(myId2, projectRef, updated, Latest(schema1.id))
        val schemaSegment = IriSegment(schema1.id)
        resources.update(IriSegment(myId2), projectRef, Some(schemaSegment), 1L, updated).accepted shouldEqual
          ResourceGen.resourceFor(expectedData, types = types, subject = subject, rev = 2L, am = am, base = projBase)
      }

      "succeed without specifying the schema" in {
        val updated      = source.removeKeys(keywords.id) deepMerge json"""{"number": 65}"""
        val expectedData = ResourceGen.resource(myId2, projectRef, updated, Latest(schema1.id))
        resources.update(StringSegment("nxv:myid2"), projectRef, None, 2L, updated).accepted shouldEqual
          ResourceGen.resourceFor(expectedData, types = types, subject = subject, rev = 3L, am = am, base = projBase)
      }

      "reject if it doesn't exists" in {
        resources
          .update(IriSegment(nxv + "other"), projectRef, None, 1L, json"""{"a": "b"}""")
          .rejectedWith[ResourceNotFound]
      }

      "reject if the revision passed is incorrect" in {
        resources.update(IriSegment(myId), projectRef, None, 3L, json"""{"a": "b"}""").rejected shouldEqual
          IncorrectRev(provided = 3L, expected = 1L)
      }

      "reject if deprecated" in {
        resources.deprecate(IriSegment(myId3), projectRef, None, 1L).accepted
        resources
          .update(IriSegment(myId3), projectRef, None, 2L, json"""{"a": "b"}""")
          .rejectedWith[ResourceIsDeprecated]
        resources
          .update(StringSegment("nxv:myid3"), projectRef, None, 2L, json"""{"a": "b"}""")
          .rejectedWith[ResourceIsDeprecated]
      }

      "reject if schemas do not match" in {
        resources
          .update(IriSegment(myId2), projectRef, Some(IriSegment(schemas.resources)), 3L, json"""{"a": "b"}""")
          .rejectedWith[UnexpectedResourceSchema]
      }

      "reject if it does not validate against its schema" in {
        val wrongSource   = source.removeKeys(keywords.id) deepMerge json"""{"number": "wrong"}"""
        val schemaSegment = IriSegment(schema1.id)
        resources
          .update(IriSegment(myId2), projectRef, Some(schemaSegment), 3L, wrongSource)
          .rejectedWith[InvalidResource]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        resources.update(IriSegment(myId), projectRef, None, 2L, source).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        resources.update(IriSegment(myId), projectDeprecated.value.ref, None, 2L, source).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(projectDeprecated.value.ref))
      }
    }

    "tagging a resource" should {

      "succeed" in {
        val expectedData = ResourceGen.resource(myId, projectRef, source, resourceSchema)
        val resource     =
          resources.tag(IriSegment(myId), projectRef, Some(IriSegment(schemas.resources)), tag, 1L, 1L).accepted
        resource shouldEqual
          ResourceGen.resourceFor(
            expectedData,
            tags = Map(tag -> 1L),
            types = types,
            subject = subject,
            rev = 2L,
            am = am,
            base = projBase
          )
      }

      "reject if it doesn't exists" in {
        resources.tag(IriSegment(nxv + "other"), projectRef, None, tag, 1L, 1L).rejectedWith[ResourceNotFound]
      }

      "reject if the revision passed is incorrect" in {
        resources.tag(IriSegment(myId), projectRef, None, tag, 1L, 3L).rejected shouldEqual
          IncorrectRev(provided = 3L, expected = 2L)
      }

      "reject if deprecated" in {
        resources.tag(IriSegment(myId3), projectRef, None, tag, 2L, 2L).rejectedWith[ResourceIsDeprecated]
        resources.tag(StringSegment("nxv:myid3"), projectRef, None, tag, 2L, 2L).rejectedWith[ResourceIsDeprecated]
      }

      "reject if schemas do not match" in {
        resources
          .tag(IriSegment(myId2), projectRef, Some(IriSegment(schemas.resources)), tag, 2L, 3L)
          .rejectedWith[UnexpectedResourceSchema]
      }

      "reject if tag revision not found" in {
        resources
          .tag(IriSegment(myId), projectRef, Some(IriSegment(schemas.resources)), tag, 6L, 2L)
          .rejected shouldEqual
          RevisionNotFound(provided = 6L, current = 2L)
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        resources.tag(IriSegment(myId), projectRef, None, tag, 2L, 1L).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        resources.tag(IriSegment(myId), projectDeprecated.value.ref, None, tag, 2L, 1L).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(projectDeprecated.value.ref))
      }
    }

    "deprecating a resource" should {

      "succeed" in {
        val sourceWithId  = source deepMerge json"""{"@id": "$myId4"}"""
        val expectedData  = ResourceGen.resource(myId4, projectRef, sourceWithId, Latest(schema1.id))
        val schemaSegment = IriSegment(schema1.id)
        val resource      = resources.deprecate(IriSegment(myId4), projectRef, Some(schemaSegment), 1L).accepted
        resource shouldEqual
          ResourceGen.resourceFor(
            expectedData,
            types = types,
            subject = subject,
            rev = 2L,
            deprecated = true,
            am = am,
            base = projBase
          )
      }

      "reject if it doesn't exists" in {
        resources.deprecate(IriSegment(nxv + "other"), projectRef, None, 1L).rejectedWith[ResourceNotFound]
      }

      "reject if the revision passed is incorrect" in {
        resources.deprecate(IriSegment(myId), projectRef, None, 3L).rejected shouldEqual
          IncorrectRev(provided = 3L, expected = 2L)
      }

      "reject if deprecated" in {
        resources.deprecate(IriSegment(myId4), projectRef, None, 2L).rejectedWith[ResourceIsDeprecated]
        resources.deprecate(StringSegment("nxv:myid4"), projectRef, None, 2L).rejectedWith[ResourceIsDeprecated]
      }

      "reject if schemas do not match" in {
        resources
          .deprecate(IriSegment(myId2), projectRef, Some(IriSegment(schemas.resources)), 3L)
          .rejectedWith[UnexpectedResourceSchema]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        resources.deprecate(IriSegment(myId), projectRef, None, 1L).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        resources.deprecate(IriSegment(myId), projectDeprecated.value.ref, None, 1L).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(projectDeprecated.value.ref))
      }

    }

    "fetching a resource" should {
      val expectedData = ResourceGen.resource(myId, projectRef, source, resourceSchema)

      "succeed" in {
        forAll(List(None, Some(IriSegment(schemas.resources)))) { schema =>
          resources.fetch(IriSegment(myId), projectRef, schema).accepted.value shouldEqual
            ResourceGen.resourceFor(
              expectedData,
              tags = Map(tag -> 1L),
              types = types,
              subject = subject,
              rev = 2L,
              am = am,
              base = projBase
            )
        }
      }

      "succeed by tag" in {
        forAll(List(None, Some(IriSegment(schemas.resources)))) { schema =>
          resources.fetchBy(StringSegment("nxv:myid"), projectRef, schema, tag).accepted.value shouldEqual
            ResourceGen.resourceFor(expectedData, types = types, subject = subject, rev = 1L, am = am, base = projBase)
        }
      }

      "succeed by rev" in {
        forAll(List(None, Some(IriSegment(schemas.resources)))) { schema =>
          resources.fetchAt(IriSegment(myId), projectRef, schema, 1L).accepted.value shouldEqual
            ResourceGen.resourceFor(expectedData, types = types, subject = subject, rev = 1L, am = am, base = projBase)
        }
      }

      "reject if tag does not exist" in {
        val otherTag = Label.unsafe("other")
        forAll(List(None, Some(IriSegment(schemas.resources)))) { schema =>
          resources.fetchBy(IriSegment(myId), projectRef, schema, otherTag).rejected shouldEqual TagNotFound(otherTag)
        }
      }

      "reject if revision does not exist" in {
        forAll(List(None, Some(IriSegment(schemas.resources)))) { schema =>
          resources.fetchAt(IriSegment(myId), projectRef, schema, 5L).rejected shouldEqual
            RevisionNotFound(provided = 5L, current = 2L)
        }
      }

      "return none if resource does not exist" in {
        val myId = nxv + "notFound"
        resources.fetch(IriSegment(myId), projectRef, None).accepted shouldEqual None
        resources.fetchBy(IriSegment(myId), projectRef, None, tag).accepted shouldEqual None
        resources.fetchAt(IriSegment(myId), projectRef, None, 2L).accepted shouldEqual None
      }

      "return none if schema is not resource schema" in {
        val schemaSegment = IriSegment(schema1.id)
        resources.fetch(IriSegment(myId), projectRef, Some(schemaSegment)).accepted shouldEqual None
        resources.fetchBy(IriSegment(myId), projectRef, Some(schemaSegment), tag).accepted shouldEqual None
        resources.fetchAt(IriSegment(myId), projectRef, Some(schemaSegment), 2L).accepted shouldEqual None
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        resources.fetch(IriSegment(myId), projectRef, None).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "return none if resource does not exist on deprecated project" in {
        resources.fetch(IriSegment(myId), projectDeprecated.value.ref, None).accepted shouldEqual None
      }
    }

    "fetching SSE" should {
      val allEvents = List(
        (myId, ClassUtils.simpleName(ResourceCreated), Sequence(1L)),
        (myId2, ClassUtils.simpleName(ResourceCreated), Sequence(2L)),
        (myId3, ClassUtils.simpleName(ResourceCreated), Sequence(3L)),
        (myId4, ClassUtils.simpleName(ResourceCreated), Sequence(4L)),
        (myId5, ClassUtils.simpleName(ResourceCreated), Sequence(5L)),
        (myId6, ClassUtils.simpleName(ResourceCreated), Sequence(6L)),
        (myId7, ClassUtils.simpleName(ResourceCreated), Sequence(7L)),
        (myId2, ClassUtils.simpleName(ResourceUpdated), Sequence(8L)),
        (myId2, ClassUtils.simpleName(ResourceUpdated), Sequence(9L)),
        (myId3, ClassUtils.simpleName(ResourceDeprecated), Sequence(10L)),
        (myId, ClassUtils.simpleName(ResourceTagAdded), Sequence(11L)),
        (myId4, ClassUtils.simpleName(ResourceDeprecated), Sequence(12L))
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
            .take(12L)
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
            .take(10L)
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
