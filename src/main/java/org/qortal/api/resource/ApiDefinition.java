package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;

@OpenAPIDefinition(
		info = @Info( title = "Qortal API", description = "NOTE: byte-arrays are encoded in Base58" ),
		tags = {
			@Tag(name = "Addresses"),
			@Tag(name = "Admin"),
			@Tag(name = "Arbitrary"),
			@Tag(name = "Assets"),
			@Tag(name = "Automated Transactions"),
			@Tag(name = "Blocks"),
			@Tag(name = "Cross-Chain"),
			@Tag(name = "Groups"),
			@Tag(name = "Names"),
			@Tag(name = "Payments"),
			@Tag(name = "Peers"),
			@Tag(name = "Transactions"),
			@Tag(name = "Utilities")
		},
		extensions = {
			@Extension(name = "translation", properties = {
					@ExtensionProperty(name="title.key", value="info:title")
			})
		}
)
public class ApiDefinition {
}