package com.wavesplatform.dex.api.http

import com.github.swagger.akka.SwaggerHttpService
import com.github.swagger.akka.model.{Info, License}
import com.wavesplatform.dex.Version
import com.wavesplatform.dex.api.http.headers.`X-Api-Key`
import io.swagger.models.Swagger
import io.swagger.models.auth.{ApiKeyAuthDefinition, In}

class SwaggerDocService(val apiClasses: Set[Class[_]], override val host: String) extends SwaggerHttpService {

  override val info: Info = Info(
    description = "The Web Interface to the Turtle Network Matcher Server API",
    version = Version.VersionString,
    title = "Turtle Network Matcher Server",
    license = Some(License("MIT License", "https://github.com/turtlenetwork/dex/blob/master/LICENSE"))
  )

  // Let swagger-ui determine the host and port
  override val swaggerConfig: Swagger = new Swagger()
    .basePath(SwaggerHttpService.prependSlashIfNecessary(basePath))
    .info(info)
    .securityDefinition(SwaggerDocService.apiKeyDefinitionName, new ApiKeyAuthDefinition(`X-Api-Key`.name, In.HEADER))

  override val unwantedDefinitions = Seq("Function1", "Function1RequestContextFutureRouteResult")
}

object SwaggerDocService {
  final val apiKeyDefinitionName = "API Key"
}
