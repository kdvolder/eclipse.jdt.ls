/*******************************************************************************
 * Copyright (c) 2016-2018 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.handlers;

import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logException;
import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logInfo;
import static org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.internal.launching.StandardVMType;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstallType;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.VMStandin;
import org.eclipse.jdt.ls.core.internal.BuildWorkspaceStatus;
import org.eclipse.jdt.ls.core.internal.CancellableProgressMonitor;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection.JavaLanguageClient;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.JobHelpers;
import org.eclipse.jdt.ls.core.internal.LanguageServerWorkingCopyOwner;
import org.eclipse.jdt.ls.core.internal.ServiceStatus;
import org.eclipse.jdt.ls.core.internal.lsp.DidChangeWorkspaceFoldersParams;
import org.eclipse.jdt.ls.core.internal.lsp.JavaProtocolExtensions;
import org.eclipse.jdt.ls.core.internal.lsp.WorkspaceFoldersProposedService;
import org.eclipse.jdt.ls.core.internal.managers.ContentProviderManager;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.jdt.ls.core.internal.preferences.Preferences;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WillSaveTextDocumentParams;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * @author Gorkem Ercan
 *
 */
public class JDTLanguageServer implements LanguageServer, TextDocumentService, WorkspaceService, WorkspaceFoldersProposedService, JavaProtocolExtensions {

	ExecutorService executor = Executors.newSingleThreadExecutor();

	public static final String JAVA_LSP_JOIN_ON_COMPLETION = "java.lsp.joinOnCompletion";
	/**
	 * Exit code returned when JDTLanguageServer is forced to exit.
	 */
	private static final int FORCED_EXIT_CODE = 1;
	private JavaClientConnection client;
	private ProjectsManager pm;
	private LanguageServerWorkingCopyOwner workingCopyOwner;
	private PreferenceManager preferenceManager;
	private DocumentLifeCycleHandler documentLifeCycleHandler;

	private Set<String> registeredCapabilities = new HashSet<>(3);

	public LanguageServerWorkingCopyOwner getWorkingCopyOwner() {
		return workingCopyOwner;
	}

	public JDTLanguageServer(ProjectsManager projects, PreferenceManager preferenceManager) {
		this.pm = projects;
		this.preferenceManager = preferenceManager;
	}

	public void connectClient(JavaLanguageClient client) {
		this.client = new JavaClientConnection(client);
		this.workingCopyOwner = new LanguageServerWorkingCopyOwner(this.client);
		pm.setConnection(client);
		WorkingCopyOwner.setPrimaryBufferProvider(this.workingCopyOwner);
		this.documentLifeCycleHandler = new DocumentLifeCycleHandler(this.client, preferenceManager, pm, true);
	}

	//For testing purposes
	public void disconnectClient() {
		this.client.disconnect();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.LanguageServer#initialize(org.eclipse.lsp4j.InitializeParams)
	 */
	@Override
	public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
		logInfo(">> initialize");
		InitHandler handler = new InitHandler(pm, preferenceManager, client);
		return CompletableFuture.completedFuture(handler.initialize(params));
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.LanguageServer#initialized(org.eclipse.lsp4j.InitializedParams)
	 */
	@Override
	public void initialized(InitializedParams params) {
		executor.execute(() -> client.executeCommand("say.hello", "JDT redhat says hello"));
		if (preferenceManager.getClientPreferences().isWorkspaceFoldersSupported()) {
			registerCapability(WorkspaceFoldersProposedService.CAPABILITY_ID, WorkspaceFoldersProposedService.CAPABILITY_NAME);
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.LanguageServer#shutdown()
	 */
	@Override
	public CompletableFuture<Object> shutdown() {
		logInfo(">> shutdown");
		return computeAsync((cc) -> {
			try {
				InitHandler.removeWorkspaceDiagnosticsHandler();
				ResourcesPlugin.getWorkspace().save(true, toMonitor(cc));
			} catch (CoreException e) {
				logException(e.getMessage(), e);
			}
			return new Object();
		});
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.LanguageServer#exit()
	 */
	@Override
	public void exit() {
		logInfo(">> exit");
		JavaLanguageServerPlugin.getLanguageServer().exit();
		Executors.newSingleThreadScheduledExecutor().schedule(() -> {
			logInfo("Forcing exit after 1 min.");
			System.exit(FORCED_EXIT_CODE);
		}, 1, TimeUnit.MINUTES);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.LanguageServer#getTextDocumentService()
	 */
	@Override
	public TextDocumentService getTextDocumentService() {
		return this;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.LanguageServer#getWorkspaceService()
	 */
	@Override
	public WorkspaceService getWorkspaceService() {
		return this;
	}

	@JsonDelegate
	public JavaProtocolExtensions getJavaExtensions() {
		return this;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.WorkspaceService#symbol(org.eclipse.lsp4j.WorkspaceSymbolParams)
	 */
	@Override
	public CompletableFuture<List<? extends SymbolInformation>> symbol(WorkspaceSymbolParams params) {
		logInfo(">> workspace/symbol");
		WorkspaceSymbolHandler handler = new WorkspaceSymbolHandler();
		return computeAsync((cc) -> {
			return handler.search(params.getQuery(), toMonitor(cc));
		});
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.WorkspaceService#didChangeConfiguration(org.eclipse.lsp4j.DidChangeConfigurationParams)
	 */
	@Override
	public void didChangeConfiguration(DidChangeConfigurationParams params) {
		logInfo(">> workspace/didChangeConfiguration");
		Object settings = params.getSettings();
		if (settings instanceof Map) {
			@SuppressWarnings("unchecked")
			Preferences prefs = Preferences.createFrom((Map<String, Object>) settings);
			preferenceManager.update(prefs);
		}
		JobHelpers.waitForInitializeJobs();
		if (preferenceManager.getClientPreferences().isWorkspaceSymbolDynamicRegistered()) {
			JavaLanguageServerPlugin.getInstance().registerCapability(Preferences.WORKSPACE_SYMBOL_ID, Preferences.WORKSPACE_SYMBOL);
		}
		if (preferenceManager.getClientPreferences().isDocumentSymbolDynamicRegistered()) {
			JavaLanguageServerPlugin.getInstance().registerCapability(Preferences.DOCUMENT_SYMBOL_ID, Preferences.DOCUMENT_SYMBOL);
		}
		if (preferenceManager.getClientPreferences().isCodeActionDynamicRegistered()) {
			JavaLanguageServerPlugin.getInstance().registerCapability(Preferences.CODE_ACTION_ID, Preferences.CODE_ACTION);
		}
		if (preferenceManager.getClientPreferences().isDefinitionDynamicRegistered()) {
			JavaLanguageServerPlugin.getInstance().registerCapability(Preferences.DEFINITION_ID, Preferences.DEFINITION);
		}
		if (preferenceManager.getClientPreferences().isHoverDynamicRegistered()) {
			JavaLanguageServerPlugin.getInstance().registerCapability(Preferences.HOVER_ID, Preferences.HOVER);
		}
		if (preferenceManager.getClientPreferences().isReferencesDynamicRegistered()) {
			JavaLanguageServerPlugin.getInstance().registerCapability(Preferences.REFERENCES_ID, Preferences.REFERENCES);
		}
		if (preferenceManager.getClientPreferences().isDocumentHighlightDynamicRegistered()) {
			JavaLanguageServerPlugin.getInstance().registerCapability(Preferences.DOCUMENT_HIGHLIGHT_ID, Preferences.DOCUMENT_HIGHLIGHT);
		}
		if (preferenceManager.getClientPreferences().isFormattingDynamicRegistrationSupported()) {
			toggleCapability(preferenceManager.getPreferences().isJavaFormatEnabled(), Preferences.FORMATTING_ID, Preferences.TEXT_DOCUMENT_FORMATTING, null);
		}
		if (preferenceManager.getClientPreferences().isRangeFormattingDynamicRegistrationSupported()) {
			toggleCapability(preferenceManager.getPreferences().isJavaFormatEnabled(), Preferences.FORMATTING_RANGE_ID, Preferences.TEXT_DOCUMENT_RANGE_FORMATTING, null);
		}
		if (preferenceManager.getClientPreferences().isCodeLensDynamicRegistrationSupported()) {
			toggleCapability(preferenceManager.getPreferences().isCodeLensEnabled(), Preferences.CODE_LENS_ID, Preferences.TEXT_DOCUMENT_CODE_LENS, new CodeLensOptions(true));
		}
		if (preferenceManager.getClientPreferences().isSignatureHelpDynamicRegistrationSupported()) {
			toggleCapability(preferenceManager.getPreferences().isSignatureHelpEnabled(), Preferences.SIGNATURE_HELP_ID, Preferences.TEXT_DOCUMENT_SIGNATURE_HELP, SignatureHelpHandler.createOptions());
		}
		if (preferenceManager.getClientPreferences().isRenameDynamicRegistrationSupported()) {
			toggleCapability(preferenceManager.getPreferences().isRenameEnabled(), Preferences.RENAME_ID, Preferences.TEXT_DOCUMENT_RENAME, null);
			if (preferenceManager.getPreferences().isRenameEnabled()) {
				registerCapability(Preferences.RENAME_ID, Preferences.TEXT_DOCUMENT_RENAME);
			} else {
				unregisterCapability(Preferences.RENAME_ID, Preferences.TEXT_DOCUMENT_RENAME);
			}
		}
		if (preferenceManager.getClientPreferences().isExecuteCommandDynamicRegistrationSupported()) {
			toggleCapability(preferenceManager.getPreferences().isExecuteCommandEnabled(), Preferences.EXECUTE_COMMAND_ID, Preferences.WORKSPACE_EXECUTE_COMMAND,
					new ExecuteCommandOptions(new ArrayList<>(WorkspaceExecuteCommandHandler.getCommands())));
		}
		boolean jvmChanged = false;
		try {
			jvmChanged = configureVM();
		} catch (Exception e) {
			JavaLanguageServerPlugin.logException(e.getMessage(), e);
		}
		try {
			boolean autoBuildChanged = pm.setAutoBuilding(preferenceManager.getPreferences().isAutobuildEnabled());
			if (jvmChanged) {
				buildWorkspace(true);
			} else if (autoBuildChanged) {
				buildWorkspace(false);
			}
		} catch (CoreException e) {
			JavaLanguageServerPlugin.logException(e.getMessage(), e);
		}
		logInfo(">>New configuration: " + settings);
	}

	public boolean configureVM() throws CoreException {
		String javaHome = preferenceManager.getPreferences().getJavaHome();
		if (javaHome != null) {
			File jvmHome = new File(javaHome);
			if (jvmHome.isDirectory()) {
				IVMInstall defaultVM = JavaRuntime.getDefaultVMInstall();
				File location = defaultVM.getInstallLocation();
				if (!location.equals(jvmHome)) {
					IVMInstall vm = findVM(jvmHome);
					if (vm == null) {
						IVMInstallType installType = JavaRuntime.getVMInstallType(StandardVMType.ID_STANDARD_VM_TYPE);
						long unique = System.currentTimeMillis();
						while (installType.findVMInstall(String.valueOf(unique)) != null) {
							unique++;
						}
						String vmId = String.valueOf(unique);
						VMStandin vmStandin = new VMStandin(installType, vmId);
						String name = StringUtils.defaultIfBlank(jvmHome.getName(), "JRE");
						vmStandin.setName(name);
						vmStandin.setInstallLocation(jvmHome);
						vm = vmStandin.convertToRealVM();
					}
					JavaRuntime.setDefaultVMInstall(vm, new NullProgressMonitor());
					JDTUtils.setCompatibleVMs(vm.getId());
					return true;
				}
			}
		}
		return false;
	}

	private IVMInstall findVM(File jvmHome) {
		IVMInstallType[] types = JavaRuntime.getVMInstallTypes();
		for (IVMInstallType type : types) {
			IVMInstall[] installs = type.getVMInstalls();
			for (IVMInstall install : installs) {
				if (jvmHome.equals(install.getInstallLocation())) {
					return install;
				}
			}
		}
		return null;
	}

	private void toggleCapability(boolean enabled, String id, String capability, Object options) {
		if (enabled) {
			registerCapability(id, capability, options);
		} else {
			unregisterCapability(id, capability);
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.WorkspaceService#didChangeWatchedFiles(org.eclipse.lsp4j.DidChangeWatchedFilesParams)
	 */
	@Override
	public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
		logInfo(">> workspace/didChangeWatchedFiles ");
		WorkspaceEventsHandler handler = new WorkspaceEventsHandler(pm, client);
		handler.didChangeWatchedFiles(params);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.WorkspaceService#executeCommand(org.eclipse.lsp4j.ExecuteCommandParams)
	 */
	@Override
	public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
		logInfo(">> workspace/executeCommand " + (params == null ? null : params.getCommand()));
		WorkspaceExecuteCommandHandler handler = new WorkspaceExecuteCommandHandler();
		return computeAsync((cc) -> {
			return handler.executeCommand(params, toMonitor(cc));
		});
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#completion(org.eclipse.lsp4j.TextDocumentPositionParams)
	 */
	@Override
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(TextDocumentPositionParams position) {
		logInfo(">> document/completion");
		CompletionHandler handler = new CompletionHandler();
		final IProgressMonitor[] monitors = new IProgressMonitor[1];
		CompletableFuture<Either<List<CompletionItem>, CompletionList>> result = computeAsync((cc) -> {
			monitors[0] = toMonitor(cc);
			if (Boolean.getBoolean(JAVA_LSP_JOIN_ON_COMPLETION)) {
				try {
					Job.getJobManager().join(DocumentLifeCycleHandler.DOCUMENT_LIFE_CYCLE_JOBS, monitors[0]);
				} catch (OperationCanceledException ignorable) {
					// No need to pollute logs when query is cancelled
				} catch (InterruptedException e) {
					JavaLanguageServerPlugin.logException(e.getMessage(), e);
				}
			}
			return handler.completion(position, monitors[0]);
		});
		result.join();
		if (monitors[0].isCanceled()) {
			result.cancel(true);
		}
		return result;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#resolveCompletionItem(org.eclipse.lsp4j.CompletionItem)
	 */
	@Override
	public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
		logInfo(">> document/resolveCompletionItem");
		CompletionResolveHandler handler = new CompletionResolveHandler(preferenceManager);
		final IProgressMonitor[] monitors = new IProgressMonitor[1];
		CompletableFuture<CompletionItem> result = computeAsync((cc) -> {
			monitors[0] = toMonitor(cc);
			if ((Boolean.getBoolean(JAVA_LSP_JOIN_ON_COMPLETION))) {
				try {
					Job.getJobManager().join(DocumentLifeCycleHandler.DOCUMENT_LIFE_CYCLE_JOBS, monitors[0]);
				} catch (OperationCanceledException ignorable) {
					// No need to pollute logs when query is cancelled
				} catch (InterruptedException e) {
					JavaLanguageServerPlugin.logException(e.getMessage(), e);
				}
			}
			return handler.resolve(unresolved, monitors[0]);
		});
		result.join();
		if (monitors[0].isCanceled()) {
			result.cancel(true);
		}
		return result;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#hover(org.eclipse.lsp4j.TextDocumentPositionParams)
	 */
	@Override
	public CompletableFuture<Hover> hover(TextDocumentPositionParams position) {
		logInfo(">> document/hover");
		HoverHandler handler = new HoverHandler(this.preferenceManager);
		return computeAsync((cc) -> handler.hover(position, toMonitor(cc)));
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#signatureHelp(org.eclipse.lsp4j.TextDocumentPositionParams)
	 */
	@Override
	public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
		logInfo(">> document/signatureHelp");
		SignatureHelpHandler handler = new SignatureHelpHandler(preferenceManager);
		return computeAsync((cc) -> handler.signatureHelp(position, toMonitor(cc)));
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#definition(org.eclipse.lsp4j.TextDocumentPositionParams)
	 */
	@Override
	public CompletableFuture<List<? extends Location>> definition(TextDocumentPositionParams position) {
		logInfo(">> document/definition");
		NavigateToDefinitionHandler handler = new NavigateToDefinitionHandler(this.preferenceManager);
		return computeAsync((cc) -> {
			IProgressMonitor monitor = toMonitor(cc);
			try {
				Job.getJobManager().join(DocumentLifeCycleHandler.DOCUMENT_LIFE_CYCLE_JOBS, monitor);
			} catch (OperationCanceledException ignorable) {
				// No need to pollute logs when query is cancelled
			} catch (InterruptedException e) {
				JavaLanguageServerPlugin.logException(e.getMessage(), e);
			}
			return handler.definition(position, toMonitor(cc));
		});
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#references(org.eclipse.lsp4j.ReferenceParams)
	 */
	@Override
	public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
		logInfo(">> document/references");
		ReferencesHandler handler = new ReferencesHandler(this.preferenceManager);
		return computeAsync((cc) -> handler.findReferences(params, toMonitor(cc)));
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#documentHighlight(org.eclipse.lsp4j.TextDocumentPositionParams)
	 */
	@Override
	public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(TextDocumentPositionParams position) {
		logInfo(">> document/documentHighlight");
		DocumentHighlightHandler handler = new DocumentHighlightHandler();
		return computeAsync((cc) -> handler.documentHighlight(position, toMonitor(cc)));
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#documentSymbol(org.eclipse.lsp4j.DocumentSymbolParams)
	 */
	@Override
	public CompletableFuture<List<? extends SymbolInformation>> documentSymbol(DocumentSymbolParams params) {
		logInfo(">> document/documentSymbol");
		DocumentSymbolHandler handler = new DocumentSymbolHandler();
		return computeAsync((cc) -> handler.documentSymbol(params, toMonitor(cc)));
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#codeAction(org.eclipse.lsp4j.CodeActionParams)
	 */
	@Override
	public CompletableFuture<List<? extends Command>> codeAction(CodeActionParams params) {
		logInfo(">> document/codeAction");
		CodeActionHandler handler = new CodeActionHandler();
		return computeAsync((cc) -> {
			IProgressMonitor monitor = toMonitor(cc);
			try {
				Job.getJobManager().join(DocumentLifeCycleHandler.DOCUMENT_LIFE_CYCLE_JOBS, monitor);
			} catch (OperationCanceledException ignorable) {
				// No need to pollute logs when query is cancelled
			} catch (InterruptedException e) {
				JavaLanguageServerPlugin.logException(e.getMessage(), e);
			}
			return handler.getCodeActionCommands(params, toMonitor(cc));
		});
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#codeLens(org.eclipse.lsp4j.CodeLensParams)
	 */
	@Override
	public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
		logInfo(">> document/codeLens");
		CodeLensHandler handler = new CodeLensHandler(preferenceManager);
		return computeAsync((cc) -> {
			IProgressMonitor monitor = toMonitor(cc);
			try {
				Job.getJobManager().join(DocumentLifeCycleHandler.DOCUMENT_LIFE_CYCLE_JOBS, monitor);
			} catch (OperationCanceledException ignorable) {
				// No need to pollute logs when query is cancelled
			} catch (InterruptedException e) {
				JavaLanguageServerPlugin.logException(e.getMessage(), e);
			}
			return handler.getCodeLensSymbols(params.getTextDocument().getUri(), monitor);
		});
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#resolveCodeLens(org.eclipse.lsp4j.CodeLens)
	 */
	@Override
	public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
		logInfo(">> codeLens/resolve");
		CodeLensHandler handler = new CodeLensHandler(preferenceManager);
		return computeAsync((cc) -> {
			IProgressMonitor monitor = toMonitor(cc);
			try {
				Job.getJobManager().join(DocumentLifeCycleHandler.DOCUMENT_LIFE_CYCLE_JOBS, monitor);
			} catch (OperationCanceledException ignorable) {
				// No need to pollute logs when query is cancelled
			} catch (InterruptedException e) {
				JavaLanguageServerPlugin.logException(e.getMessage(), e);
			}
			return handler.resolve(unresolved, monitor);
		});
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#formatting(org.eclipse.lsp4j.DocumentFormattingParams)
	 */
	@Override
	public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
		logInfo(">> document/formatting");
		FormatterHandler handler = new FormatterHandler(preferenceManager);
		return computeAsync((cc) -> handler.formatting(params, toMonitor(cc)));
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#rangeFormatting(org.eclipse.lsp4j.DocumentRangeFormattingParams)
	 */
	@Override
	public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
		logInfo(">> document/rangeFormatting");
		FormatterHandler handler = new FormatterHandler(preferenceManager);
		return computeAsync((cc) -> handler.rangeFormatting(params, toMonitor(cc)));
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#onTypeFormatting(org.eclipse.lsp4j.DocumentOnTypeFormattingParams)
	 */
	@Override
	public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
		logInfo(">> document/onTypeFormatting");
		// Not yet implemented
		return null;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#rename(org.eclipse.lsp4j.RenameParams)
	 */
	@Override
	public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
		logInfo(">> document/rename");
		RenameHandler handler = new RenameHandler(preferenceManager);
		return computeAsync((cc) -> handler.rename(params, toMonitor(cc)));
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams)
	 */
	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		logInfo(">> document/didOpen");
		documentLifeCycleHandler.didOpen(params);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams)
	 */
	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		logInfo(">> document/didChange");
		documentLifeCycleHandler.didChange(params);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#didClose(org.eclipse.lsp4j.DidCloseTextDocumentParams)
	 */
	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		logInfo(">> document/didClose");
		documentLifeCycleHandler.didClose(params);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#willSaveWaitUntil(org.eclipse.lsp4j.WillSaveTextDocumentParams)
	 */
	@Override
	public CompletableFuture<List<TextEdit>> willSaveWaitUntil(WillSaveTextDocumentParams params) {
		logInfo(">> document/willSaveWailUntil");
		SaveActionHandler handler = new SaveActionHandler(preferenceManager);
		return computeAsync((cc) -> handler.willSaveWaitUntil(params, toMonitor(cc)));
	}

	/* (non-Javadoc)
	 * @see org.eclipse.lsp4j.services.TextDocumentService#didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams)
	 */
	@Override
	public void didSave(DidSaveTextDocumentParams params) {
		logInfo(">> document/didSave");
		documentLifeCycleHandler.didSave(params);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ls.core.internal.JavaProtocolExtensions#ClassFileContents(org.eclipse.lsp4j.TextDocumentIdentifier)
	 */
	@Override
	public CompletableFuture<String> classFileContents(TextDocumentIdentifier param) {
		logInfo(">> java/classFileContents");
		ContentProviderManager handler = JavaLanguageServerPlugin.getContentProviderManager();
		URI uri = JDTUtils.toURI(param.getUri());
		return computeAsync((cc) -> handler.getContent(uri, toMonitor(cc)));
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ls.core.internal.JavaProtocolExtensions#projectConfigurationUpdate(org.eclipse.lsp4j.TextDocumentIdentifier)
	 */
	@Override
	public void projectConfigurationUpdate(TextDocumentIdentifier param) {
		logInfo(">> java/projectConfigurationUpdate");
		ProjectConfigurationUpdateHandler handler = new ProjectConfigurationUpdateHandler(pm);
		handler.updateConfiguration(param);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ls.core.internal.JavaProtocolExtensions#buildWorkspace(boolean)
	 */
	@Override
	public CompletableFuture<BuildWorkspaceStatus> buildWorkspace(boolean forceReBuild) {
		logInfo(">> java/buildWorkspace (" + (forceReBuild ? "full)" : "incremental)"));
		BuildWorkspaceHandler handler = new BuildWorkspaceHandler(client, pm);
		return computeAsync((cc) -> handler.buildWorkspace(forceReBuild, toMonitor(cc)));
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ls.core.internal.lsp.WorkspaceServiceProposed#didChangeWorkspaceFolders(org.eclipse.jdt.ls.core.internal.lsp.DidChangeWorkspaceFoldersParams)
	 */
	@Override
	public void didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams params) {
		logInfo(">> java/didChangeWorkspaceFolders");
		WorkspaceFolderChangeHandler handler = new WorkspaceFolderChangeHandler(pm);
		handler.update(params);

	}

	public void sendStatus(ServiceStatus serverStatus, String status) {
		if (client != null) {
			client.sendStatus(serverStatus, status);
		}
	}

	private IProgressMonitor toMonitor(CancelChecker checker) {
		return new CancellableProgressMonitor(checker);
	}

	public void unregisterCapability(String id, String method) {
		if (registeredCapabilities.remove(id)) {
			Unregistration unregistration = new Unregistration(id, method);
			UnregistrationParams unregistrationParams = new UnregistrationParams(Collections.singletonList(unregistration));
			client.unregisterCapability(unregistrationParams);
		}
	}

	public void registerCapability(String id, String method) {
		registerCapability(id, method, null);
	}

	public void registerCapability(String id, String method, Object options) {
		if (registeredCapabilities.add(id)) {
			Registration registration = new Registration(id, method, options);
			RegistrationParams registrationParams = new RegistrationParams(Collections.singletonList(registration));
			client.registerCapability(registrationParams);
		}
	}

	public JavaClientConnection getClientConnection() {
		return client;
	}

}
