package com.content_assist_extractor;

import java.util.ArrayList;
import java.util.List;
import java.io.FileWriter;
import java.io.IOException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.ui.PartInitException;
import org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext;
import org.eclipse.jdt.ui.text.java.JavaContentAssistInvocationContext;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jdt.ui.text.java.CompletionProposalCollector;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

import org.eclipse.jdt.internal.ui.javaeditor.CompilationUnitEditor;
import org.eclipse.jdt.internal.ui.text.java.JavaAllCompletionProposalComputer;
import org.eclipse.jdt.internal.ui.text.java.JavaMethodCompletionProposal;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class CompletionProposalExtractor extends JavaAllCompletionProposalComputer  {
	
	final private String PROJECT_NAME = "twitter4j";

	/*
	 * whether to extract function calls bindings or not
	 */
	final private boolean EXTRACT_BINDINGS = true;
	
	/*
	 * output more logs
	 */
	final private boolean VERBOSE = false;
	
	@Override
	public List<ICompletionProposal> computeCompletionProposals(ContentAssistInvocationContext context, IProgressMonitor monitor)  {
		JSONArray allProposals = new JSONArray();
		List<String> methodString = new ArrayList<String>();
		
		// Get the root of the workspace	
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		IWorkspaceRoot root = workspace.getRoot();
		
		IProject[] projects = root.getProjects();
		
		int i = 0;
		for (IProject project : projects) {
			IPackageFragment[] packages = getProjectFragments(project.getName());

			for (IPackageFragment packageFragment : packages) {
				try {
					for (final ICompilationUnit compilationUnit : packageFragment.getCompilationUnits()) {
						// Parsing the current compilation unit (i.e., java file)
						String cuName = compilationUnit.getElementName() + " ( " + packageFragment.getElementName() + " )";
						System.out.println(cuName);
						System.out.println("=".repeat(cuName.length()));
						
						// Get CompilationUnit AST
						ASTParser parser = ASTParser.newParser(AST.JLS13);
						parser.setSource(compilationUnit);
						parser.setKind(ASTParser.K_COMPILATION_UNIT);
						if (EXTRACT_BINDINGS) {
							parser.setResolveBindings(true);
						}
						final CompilationUnit cu = (CompilationUnit) parser.createAST(null);
						
						// Load compilation unit in the editor to build a content assist collector
						// CompilationUnitEditor cuEditor = (CompilationUnitEditor) JavaUI.openInEditor(compilationUnit);
						// ISourceViewer viewer = cuEditor.getViewer();
						// IDocument document = viewer.getDocument();
						
						// Build the content assist collector using the current context of the CU
						JavaContentAssistInvocationContext javaContext = new JavaContentAssistInvocationContext(compilationUnit);
						CompletionProposalCollector collector = createCollector(javaContext);
						collector.setInvocationContext(javaContext);
						
						cu.accept(new ASTVisitor() {
							public boolean visit(MethodDeclaration node) {
								// Get the method declaration + body on one single line
								methodString.add(node.toString().replaceAll("[^\\S ]+", " "));
								String funcDeclName = node.getName().toString();
								
								if (VERBOSE) {
									System.out.println("- Function decl. : " + funcDeclName);
								}
								
								JSONArray funcCalls = new JSONArray();
								node.accept(new ASTVisitor() {
									public boolean visit(MethodInvocation node) {
										// get the offset of the function call
										int currentNodePosition = node.getStartPosition();
										String beforeMethodInv = node.toString().split(node.getName().toString())[0];
										Integer offset = currentNodePosition + beforeMethodInv.length();

										String fCallBinding;
										if (EXTRACT_BINDINGS) {
											try {
												fCallBinding = node.resolveMethodBinding().getKey().toString();
											} catch (NullPointerException e) { 
												fCallBinding = "UNK";
											}
										}
										
										if (VERBOSE) {
											System.out.println("\tFunction call: " + node.getName());
											System.out.println("\tFunction call binding: " + fCallBinding);
											System.out.println("\tInvocation offset:" + offset + "\n");
										}
										
										JSONObject funcCallData = new JSONObject();
										funcCallData.put("name", node.getName().toString());
										if (EXTRACT_BINDINGS) {
											funcCallData.put("binding", fCallBinding);
										}
										
										try {
											compilationUnit.codeComplete(offset, collector);
											ICompletionProposal[] javaProposals = collector.getJavaCompletionProposals();
											JSONArray props = new JSONArray();
											for (ICompletionProposal prop : javaProposals) {
												if (prop instanceof JavaMethodCompletionProposal) {
													String proposalName = ((JavaMethodCompletionProposal) prop).getJavaElement().getElementName();
													props.add(proposalName);
												}
											}
											funcCallData.put("proposals", props);
										} catch (JavaModelException | NullPointerException e) { }
										
										funcCalls.add(funcCallData);
										return true;
									} 
								});
								
								JSONObject fDeclData = new JSONObject() {{
									put("methodName", funcDeclName);
									put("calls", funcCalls);
								}};
								allProposals.add(fDeclData);
								return true;
							}
						});
						i++;
					}
				} catch (JavaModelException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		System.out.println("Number of parsed CU : " + i);
		
		try (FileWriter file = new FileWriter("static_analysis.json")) {
            file.write(allProposals.toJSONString()); 
            file.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
        
		try (FileWriter file = new FileWriter("method_tokens.txt")) {
			for (String m : methodString) {
				file.write(m + System.lineSeparator());
				file.flush();
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return super.computeCompletionProposals(context, monitor);
	}
	
	public IPackageFragment[] getProjectFragments(String projectName) {
		// Get the root of the workspace	
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		IWorkspaceRoot root = workspace.getRoot();
		// Find project in the workspace
		IProject project = root.getProject(projectName);
		IJavaProject javaProject = JavaCore.create(project);
		
		try {
			// Return all fragments of the project
			return javaProject.getPackageFragments();
		} catch (JavaModelException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}
	
	@Override
	public void sessionStarted() {}

	@Override
	public void sessionEnded() {}

}
