package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.{PullRequestActive, PullRequestClosed}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import fs2.{Pure, Stream}

import java.time.Instant

object PullRequestStream {

  def generate(projectRef: ProjectRef): Stream[Pure, Elem[GraphResource]] = {
    val base    = iri"http://localhost"
    val instant = Instant.EPOCH

    val pr1 = PullRequestActive(
      id = Label.unsafe("id1"),
      project = projectRef,
      rev = 1,
      createdAt = instant,
      createdBy = Anonymous,
      updatedAt = instant,
      updatedBy = Anonymous
    )

    val pr2 = PullRequestClosed(
      id = Label.unsafe("id2"),
      project = projectRef,
      rev = 1,
      createdAt = instant,
      createdBy = Anonymous,
      updatedAt = instant,
      updatedBy = Anonymous
    )

    Stream(
      SuccessElem(
        tpe = PullRequest.entityType,
        id = pr1.id.value,
        instant = pr1.updatedAt,
        offset = Offset.at(1L),
        value = PullRequestState.toGraphResource(pr1, base)
      ),
      DroppedElem(
        tpe = PullRequest.entityType,
        id = "dropped",
        Instant.EPOCH,
        Offset.at(2L)
      ),
      SuccessElem(
        tpe = PullRequest.entityType,
        id = pr2.id.value,
        instant = pr2.updatedAt,
        offset = Offset.at(3L),
        value = PullRequestState.toGraphResource(pr2, base)
      ),
      FailedElem(
        tpe = PullRequest.entityType,
        id = "failed",
        Instant.EPOCH,
        Offset.at(4L),
        new IllegalStateException("Something got wrong :(")
      )
    )
  }

}
