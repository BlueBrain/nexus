package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.{PullRequestActive, PullRequestClosed}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, FailedElem, SuccessElem}
import fs2.{Pure, Stream}

import java.time.Instant

object PullRequestStream {

  def generate(projectRef: ProjectRef): Stream[Pure, Elem[GraphResource]] = {
    val base    = iri"http://localhost"
    val instant = Instant.EPOCH

    val pr1 = PullRequestActive(
      id = nxv + "id1",
      project = projectRef,
      rev = 1,
      createdAt = instant,
      createdBy = Anonymous,
      updatedAt = instant,
      updatedBy = Anonymous
    )

    val pr2 = PullRequestClosed(
      id = nxv + "id2",
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
        id = pr1.id,
        project = Some(projectRef),
        instant = pr1.updatedAt,
        offset = Offset.at(1L),
        value = PullRequestState.toGraphResource(pr1, base),
        rev = 1
      ),
      DroppedElem(
        tpe = PullRequest.entityType,
        id = nxv + "dropped",
        project = Some(projectRef),
        Instant.EPOCH,
        Offset.at(2L),
        rev = 1
      ),
      SuccessElem(
        tpe = PullRequest.entityType,
        id = pr2.id,
        project = Some(projectRef),
        instant = pr2.updatedAt,
        offset = Offset.at(3L),
        value = PullRequestState.toGraphResource(pr2, base),
        rev = 1
      ),
      FailedElem(
        tpe = PullRequest.entityType,
        id = nxv + "failed",
        project = Some(projectRef),
        Instant.EPOCH,
        Offset.at(4L),
        FailureReason(new IllegalStateException("This is an error message")),
        rev = 1
      )
    )
  }

}
