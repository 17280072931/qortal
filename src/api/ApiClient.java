package api;

import globalization.ContextPaths;
import globalization.Translator;
import io.swagger.v3.jaxrs2.integration.resources.OpenApiResource;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.ws.rs.Path;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.PATCH;
import javax.ws.rs.DELETE;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import settings.Settings;

public class ApiClient {

	private class HelpInfo {

		public final Pattern pattern;
		public final String fullPath;
		public final String description;
		public final List<String> success;
		public final List<String> errors;

		public HelpInfo(Pattern pattern, String fullPath, String description, List<String> success, List<String> errors) {
			this.pattern = pattern;
			this.fullPath = fullPath;
			this.description = description;
			this.success = success;
			this.errors = errors;
		}
	}

	private static final String TRANSLATION_CONTEXT_PATH = "/Api/ApiClient";
	
	private static final Pattern COMMAND_PATTERN = Pattern.compile("^ *(?<method>GET|POST|PUT|PATCH|DELETE) *(?<path>.*)$");
	private static final Pattern HELP_COMMAND_PATTERN = Pattern.compile("^ *help *(?<command>.*)$", Pattern.CASE_INSENSITIVE);
	private static final List<Class<? extends Annotation>> HTTP_METHOD_ANNOTATIONS = Arrays.asList(
		GET.class,
		POST.class,
		PUT.class,
		PATCH.class,
		DELETE.class
	);

	private final Translator translator;
	ApiService apiService;
	List<HelpInfo> helpInfos;

	public ApiClient(ApiService apiService, Translator translator) {
		this.apiService = apiService;
		this.translator = translator;
		this.helpInfos = getHelpInfos(apiService.getResources());
	}

	//XXX: replace singleton pattern by dependency injection?
	private static ApiClient instance;

	public static ApiClient getInstance() {
		if (instance == null) {
			instance = new ApiClient(ApiService.getInstance(), Translator.getInstance());
		}

		return instance;
	}
	
	private List<HelpInfo> getHelpInfos(Iterable<Class<?>> resources) {
		List<HelpInfo> result = new ArrayList<>();

		// scan each resource class
		for (Class<?> resource : resources) {
			if (OpenApiResource.class.isAssignableFrom(resource)) {
				continue; // ignore swagger resources
			}
			Path resourcePath = resource.getDeclaredAnnotation(Path.class);
			if (resourcePath == null) {
				continue;
			}
			String resourcePathString = resourcePath.value();

			// get translation context path for resource
			String resourceContextPath = "/";
			OpenAPIDefinition openAPIDefinition = resource.getDeclaredAnnotation(OpenAPIDefinition.class);
			if(openAPIDefinition != null)
				resourceContextPath = getContextPath(openAPIDefinition.extensions());
			
			// scan each method
			for (Method method : resource.getDeclaredMethods()) {
				Operation operationAnnotation = method.getAnnotation(Operation.class);
				if (operationAnnotation == null)
					continue;

				String description = operationAnnotation.description();
				
				// translate
				String operationContextPath = ContextPaths.combinePaths(resourceContextPath, getContextPath(operationAnnotation.extensions()));
				String operationDescriptionKey = getDescriptionTranslationKey(operationAnnotation.extensions());
				if(operationDescriptionKey != null)
					description = translator.translate(operationContextPath, operationDescriptionKey, description);
				
				// extract responses
				ArrayList success = new ArrayList();
				ArrayList errors = new ArrayList();
				for(ApiResponse response : operationAnnotation.responses()) {
					String responseDescription = response.description();
					if(StringUtils.isBlank(responseDescription))
						continue; // ignore responses without description
					
					// translate
					String responseContextPath = ContextPaths.combinePaths(operationContextPath, getContextPath(response.extensions()));
					String responseDescriptionKey = getDescriptionTranslationKey(response.extensions());
					if(responseDescriptionKey != null)
						responseDescription = translator.translate(responseContextPath, responseDescriptionKey, responseDescription);
					
					String apiErrorCode = getApiErrorCode(response.extensions());
					if(apiErrorCode != null) {
						responseDescription = translator.translate(TRANSLATION_CONTEXT_PATH, "API error response", "(API error: ${ERROR_CODE}) ${DESCRIPTION}", 
							new AbstractMap.SimpleEntry<>("ERROR_CODE", apiErrorCode), 
							new AbstractMap.SimpleEntry<>("DESCRIPTION", responseDescription)
						);
					}
					
					try {
						// try to identify response type by status code
						int responseCode = Integer.parseInt(response.responseCode());
						if(responseCode >= 400) {
							errors.add(responseDescription);
						} else {
							success.add(responseDescription);
						}
					} catch (NumberFormatException e) {						
						// try to identify response type by content
						if(response.content().length > 0) {
							Content content = response.content()[0];
							Class<?> implementation = content.schema().implementation();
							if(implementation != null && ApiErrorMessage.class.isAssignableFrom(implementation)) {
								errors.add(responseDescription);
							} else {
								success.add(responseDescription);
							}
						} else {
							success.add(responseDescription);
						}
					}
				}

				Path methodPath = method.getDeclaredAnnotation(Path.class);
				String methodPathString = (methodPath != null) ? methodPath.value() : "";

				// scan for each potential http method
				for (Class<? extends Annotation> restMethodAnnotation : HTTP_METHOD_ANNOTATIONS) {
					Annotation annotation = method.getDeclaredAnnotation(restMethodAnnotation);
					if (annotation == null) {
						continue;
					}

					HttpMethod httpMethod = annotation.annotationType().getDeclaredAnnotation(HttpMethod.class);
					String httpMethodString = httpMethod.value();

					String fullPath = httpMethodString + " " + resourcePathString + methodPathString;			
					
					Pattern pattern = Pattern.compile("^ *(" + httpMethodString + " *)?" + getHelpPatternForPath(resourcePathString + methodPathString));
					result.add(new HelpInfo(pattern, fullPath, description, success, errors));
				}
			}
		}

		// sort by path
		result.sort((h1, h2) -> h1.fullPath.compareTo(h2.fullPath));

		return result;
	}
	
	private String getApiErrorCode(Extension[] extensions) {
		if(extensions == null)
			return null;
		
		for(Extension extension : extensions) {
			if(extension.name() != null && !extension.name().isEmpty())
				continue;

			for(ExtensionProperty prop : extension.properties()) {
				if(Constants.API_ERROR_CODE_EXTENSION_NAME.equals(prop.name())) {
					return prop.value();
				}
			}
		}
		
		return null;
	}

	private String getContextPath(Extension[] extensions) {
		return getTranslationExtensionValue(extensions, Constants.TRANSLATION_PATH_EXTENSION_NAME);
	}

	private String getDescriptionTranslationKey(Extension[] extensions) {
		return getTranslationExtensionValue(extensions, Constants.TRANSLATION_ANNOTATION_DESCRIPTION_KEY);
	}

	private String getTranslationExtensionValue(Extension[] extensions, String key) {
		if(extensions == null)
			return null;
		
		for(Extension extension : extensions) {
			if(!Constants.TRANSLATION_EXTENSION_NAME.equals(extension.name()))
				continue;

			for(ExtensionProperty prop : extension.properties()) {
				if(key.equals(prop.name())) {
					return prop.value();
				}
			}
		}
		
		return null;
	}

	private String getHelpPatternForPath(String path) {
		path = path
			.replaceAll("\\.", "\\.") // escapes "." as "\."
			.replaceAll("\\{.*?\\}", ".*?"); // replace placeholders "{...}" by the "ungreedy match anything" pattern ".*?"

		// arrange the regex pattern so that it also matches partial
		StringBuilder result = new StringBuilder();
		String[] parts = path.split("/");
		for (int i = 0; i < parts.length; i++) {
			if (i != 0) {
				result.append("(/"); // opening bracket
			}
			result.append(parts[i]);
		}
		for (int i = 0; i < parts.length - 1; i++) {
			result.append(")?"); // closing bracket
		}
		return result.toString();
	}

	public String executeCommand(String command) {
		// check if this is a help command
		Matcher match = HELP_COMMAND_PATTERN.matcher(command);
		if (match.matches()) {
			command = match.group("command");
			StringBuilder result = new StringBuilder();

			boolean showAll = command.trim().equalsIgnoreCase("all");
			for (HelpInfo helpString : helpInfos) {
				if (showAll || helpString.pattern.matcher(command).matches()) {
					appendHelp(result, helpString);
				}
			}

			return result.toString();
		}
		

		match = COMMAND_PATTERN.matcher(command);
		if(!match.matches())
			return this.translator.translate(TRANSLATION_CONTEXT_PATH, "invalid command", "Invalid command! \nType 'help all' to get a list of commands.");
		
		// send the command to the API service
		String method = match.group("method");
		String path = match.group("path");
		String url = String.format("http://127.0.0.1:%d/%s", Settings.getInstance().getRpcPort(), path);
		
		Client client = ClientBuilder.newClient();
		client.property(HttpUrlConnectorProvider.SET_METHOD_WORKAROUND, true); // workaround for non-standard HTTP methods like PATCH
		WebTarget wt = client.target(url);
		Invocation.Builder builder = wt.request(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);
		Response response = builder.method(method);

		// send back result
		final String body = response.readEntity(String.class);
		final int status = response.getStatus();
		StringBuilder result = new StringBuilder();
		if(status >= 400) {
			result.append("HTTP Status ");
			result.append(status);
			if(StringUtils.isBlank(body)) {
				result.append(
					this.translator.translate(TRANSLATION_CONTEXT_PATH, "error without body", "HTTP Status ${STATUS}",
						new AbstractMap.SimpleEntry<>("STATUS", status)
					)
				);
			}else{
				result.append(
					this.translator.translate(TRANSLATION_CONTEXT_PATH, "error with body", "HTTP Status ${STATUS}: ${BODY}",
						new AbstractMap.SimpleEntry<>("STATUS", status), 
						new AbstractMap.SimpleEntry<>("BODY", body)
					)
				);
			}
			result.append("\n");
			result.append(this.translator.translate(TRANSLATION_CONTEXT_PATH, "error footer", "Type 'help all' to get a list of commands."));
		} else {
			result.append(body);
		}
		return result.toString();
	}

	private void appendHelp(StringBuilder builder, HelpInfo help) {
		builder.append(help.fullPath + "\n");
		builder.append("    " + help.description + "\n");
		if(help.success != null && help.success.size() > 0) {
			builder.append("    ");
			builder.append(this.translator.translate(TRANSLATION_CONTEXT_PATH, "help: success responses", "On success returns:"));
			builder.append("\n");
			for(String content : help.success) {
				builder.append("        " + content + "\n");
			}
		}
		if(help.errors != null && help.errors.size() > 0) {
			builder.append("    ");
			builder.append(this.translator.translate(TRANSLATION_CONTEXT_PATH, "help: failure responses", "On failure returns:"));
			builder.append("\n");
			for(String content : help.errors) {
				builder.append("        " + content + "\n");
			}
		}
	}
}
