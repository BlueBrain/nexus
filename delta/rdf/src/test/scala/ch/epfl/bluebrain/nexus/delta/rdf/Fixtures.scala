package ch.epfl.bluebrain.nexus.delta.rdf

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdOptions
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.RemoteContextNotFound
import ch.epfl.bluebrain.nexus.delta.rdf.utils.{CirceLiteral, EitherValues, Resources}
import io.circe.Json
import monix.bio.IO
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock
import org.apache.jena.iri.IRI
import org.scalatest.OptionValues

trait Fixtures extends Resources with EitherValues with CirceLiteral with OptionValues {

  val iri = iri"http://nexus.example.com/john-doé"

  // format: off
  val remoteContexts: Map[Uri, Json] =
    Map(
      Uri("http://example.com/context/0")  -> json"""{"@context": {"deprecated": {"@id": "http://schema.org/deprecated", "@type": "http://www.w3.org/2001/XMLSchema#boolean"} }}""",
      Uri("http://example.com/context/1")  -> json"""{"@context": ["http://example.com/context/11", "http://example.com/context/12"] }""",
      Uri("http://example.com/context/11") -> json"""{"@context": {"birthDate": "http://schema.org/birthDate"} }""",
      Uri("http://example.com/context/12") -> json"""{"@context": {"Other": "http://schema.org/Other"} }""",
      Uri("http://example.com/context/2")  -> json"""{"@context": {"integerAlias": "http://www.w3.org/2001/XMLSchema#integer", "type": "@type"} }""",
      Uri("http://example.com/context/3")  -> json"""{"@context": {"customid": {"@type": "@id"} } }"""
    )
  // format: on

  implicit val remoteResolution: RemoteContextResolution = resolution(remoteContexts)
  implicit val sc: Scheduler                             = Scheduler.global
  implicit val pm: CanBlock                              = CanBlock.permit
  implicit val opts: JsonLdOptions                       = JsonLdOptions()

  object vocab {
    val value                  = iri"http://example.com/"
    def +(string: String): IRI = iri"$value$string"
  }

  object base {
    val value                  = iri"http://nexus.example.com/"
    def +(string: String): IRI = iri"$value$string"
  }

  def resolution(contexts: Map[Uri, Json]): RemoteContextResolution =
    RemoteContextResolution((uri: Uri) => IO.fromEither(contexts.get(uri).toRight(RemoteContextNotFound(uri))))

}

object Fixtures extends Fixtures
