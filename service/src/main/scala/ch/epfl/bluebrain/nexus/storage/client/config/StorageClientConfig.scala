package ch.epfl.bluebrain.nexus.storage.client.config

import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Url, Urn}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._

/**
  * Configuration for [[ch.epfl.bluebrain.nexus.storage.client.StorageClient]].
  *
  * @param iri    base URL for all the HTTP calls, excluding prefix.
  * @param prefix the prefix appended to the base. It can be used to describe the service API version.
  */
final case class StorageClientConfig(iri: AbsoluteIri, prefix: String) {
  lazy val buckets: AbsoluteIri = iri + prefix + "buckets"

  /**
    * The files endpoint: /buckets/{name}/files
    *
    * @param name the storage bucket name
    */
  def files(name: String): AbsoluteIri = buckets + name + "files"

  /**
    * The attributes endpoint: /buckets/{name}/attributes
    *
    * @param name the storage bucket name
    */
  def attributes(name: String): AbsoluteIri = buckets + name + "attributes"

}

object StorageClientConfig {

  /**
    * Build [[StorageClientConfig]] from an iri
    *
    * @param iri base URL for all the HTTP calls, including prefix.
    */
  final def apply(iri: AbsoluteIri): StorageClientConfig = {
    val (prefix, path) = iri.path match {
      case Segment(p, Slash(rest)) => p  -> rest
      case rest                    => "" -> rest
    }
    iri match {
      case url: Url => StorageClientConfig(url.copy(path = path), prefix)
      case urn: Urn => StorageClientConfig(urn.copy(nss = path), prefix)
    }
  }
}
