package ch.epfl.bluebrain.nexus.ship.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef}
import doobie.implicits._
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax.EncoderOps
import io.circe.{Codec, Json}

/**
  * Allows to keep track of the original project context which will change because of project renaming / patching
  * configuration so as to be able to use them to expand iris in resource payload
  */
class OriginalProjectContext(xas: Transactors) extends FetchContext {

  implicit val projectContextCodec: Codec[ProjectContext] = deriveCodec[ProjectContext]

  override def defaultApiMappings: ApiMappings = ApiMappings.empty

  override def onRead(ref: ProjectRef): IO[ProjectContext] =
    sql"""|SELECT context
          |FROM public.ship_original_project_context
          |WHERE org = ${ref.organization}
          |AND project = ${ref.project}""".stripMargin.query[Json].unique.transact(xas.read).flatMap { json =>
      IO.fromEither(json.as[ProjectContext])
    }

  override def onCreate(ref: ProjectRef)(implicit subject: Identity.Subject): IO[ProjectContext] =
    IO.raiseError(new IllegalStateException("OnCreate should not be called in this context"))

  override def onModify(ref: ProjectRef)(implicit subject: Identity.Subject): IO[ProjectContext] =
    IO.raiseError(new IllegalStateException("OnCreate should not be called in this context"))

  def save(project: ProjectRef, context: ProjectContext): IO[Unit] =
    sql"""INSERT INTO public.ship_original_project_context (org, project, context)
         |VALUES (
         |   ${project.organization}, ${project.project}, ${context.asJson}
         |)
         |ON CONFLICT (org, project)
         |DO UPDATE set
         |  context = EXCLUDED.context;
         |""".stripMargin.update.run
      .transact(xas.write)
      .void
}
