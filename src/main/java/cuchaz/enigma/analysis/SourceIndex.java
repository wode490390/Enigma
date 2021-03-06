/*******************************************************************************
 * Copyright (c) 2015 Jeff Martin.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public
 * License v3.0 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 * <p>
 * Contributors:
 * Jeff Martin - initial API and implementation
 ******************************************************************************/

package cuchaz.enigma.analysis;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.strobel.decompiler.languages.Region;
import com.strobel.decompiler.languages.java.ast.AstNode;
import com.strobel.decompiler.languages.java.ast.CompilationUnit;
import com.strobel.decompiler.languages.java.ast.ConstructorDeclaration;
import com.strobel.decompiler.languages.java.ast.Identifier;
import com.strobel.decompiler.languages.java.ast.TypeDeclaration;
import cuchaz.enigma.gui.SourceRemapper;
import cuchaz.enigma.translation.mapping.EntryResolver;
import cuchaz.enigma.translation.mapping.ResolutionStrategy;
import cuchaz.enigma.translation.representation.entry.Entry;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SourceIndex {
	private static Pattern ANONYMOUS_INNER = Pattern.compile("\\$\\d+$");

	private String source;
	private TreeMap<Token, EntryReference<Entry<?>, Entry<?>>> tokenToReference;
	private Multimap<EntryReference<Entry<?>, Entry<?>>, Token> referenceToTokens;
	private Map<Entry<?>, Token> declarationToToken;
	private List<Integer> lineOffsets;
	private boolean ignoreBadTokens;

	public SourceIndex(String source) {
		this(source, true);
	}

	public SourceIndex(String source, boolean ignoreBadTokens) {
		this.source = source;
		this.ignoreBadTokens = ignoreBadTokens;
		this.tokenToReference = new TreeMap<>();
		this.referenceToTokens = HashMultimap.create();
		this.declarationToToken = Maps.newHashMap();
		calculateLineOffsets();
	}

	public static SourceIndex buildIndex(String sourceString, CompilationUnit sourceTree, boolean ignoreBadTokens) {
		SourceIndex index = new SourceIndex(sourceString, ignoreBadTokens);
		sourceTree.acceptVisitor(new SourceIndexVisitor(), index);

		return index;
	}

	private void calculateLineOffsets() {
		// count the lines
		this.lineOffsets = Lists.newArrayList();
		this.lineOffsets.add(0);
		for (int i = 0; i < source.length(); i++) {
			if (source.charAt(i) == '\n') {
				this.lineOffsets.add(i + 1);
			}
		}
	}

	public SourceIndex remapTo(SourceRemapper.Result result) {
		SourceIndex remapped = new SourceIndex(result.getSource(), ignoreBadTokens);

		for (Map.Entry<Entry<?>, Token> entry : declarationToToken.entrySet()) {
			remapped.declarationToToken.put(entry.getKey(), result.getRemappedToken(entry.getValue()));
		}

		for (Map.Entry<EntryReference<Entry<?>, Entry<?>>, Collection<Token>> entry : referenceToTokens.asMap().entrySet()) {
			EntryReference<Entry<?>, Entry<?>> reference = entry.getKey();
			Collection<Token> oldTokens = entry.getValue();

			Collection<Token> newTokens = oldTokens.stream()
					.map(result::getRemappedToken)
					.collect(Collectors.toList());

			remapped.referenceToTokens.putAll(reference, newTokens);
		}

		for (Map.Entry<Token, EntryReference<Entry<?>, Entry<?>>> entry : tokenToReference.entrySet()) {
			remapped.tokenToReference.put(result.getRemappedToken(entry.getKey()), entry.getValue());
		}

		return remapped;
	}

	public String getSource() {
		return this.source;
	}

	public Token getToken(AstNode node) {

		// get the text of the node
		String name = "";
		if (node instanceof Identifier) {
			name = ((Identifier) node).getName();
		}

		// get a token for this node's region
		Region region = node.getRegion();
		if (region.getBeginLine() == 0 || region.getEndLine() == 0) {
			// DEBUG
			System.err.println(String.format("WARNING: %s \"%s\" has invalid region: %s", node.getNodeType(), name, region));
			return null;
		}
		Token token = new Token(toPos(region.getBeginLine(), region.getBeginColumn()), toPos(region.getEndLine(), region.getEndColumn()), this.source);
		if (token.start == 0) {
			// DEBUG
			System.err.println(String.format("WARNING: %s \"%s\" has invalid start: %s", node.getNodeType(), name, region));
			return null;
		}

		if (node instanceof Identifier && name.indexOf('$') >= 0 && node.getParent() instanceof ConstructorDeclaration && name.lastIndexOf('$') >= 0 && !ANONYMOUS_INNER.matcher(name).matches()) {
			TypeDeclaration type = node.getParent().getParent() instanceof TypeDeclaration ? (TypeDeclaration) node.getParent().getParent() : null;
			if (type != null) {
				name = type.getName();
				token.end = token.start + name.length();
			}
		}

		// DEBUG
		// System.out.println( String.format( "%s \"%s\" region: %s", node.getNodeType(), name, region ) );

		// Tokens can have $ in name, even for top-level classes
		//if (name.lastIndexOf('$') >= 0 && this.ignoreBadTokens) {
		//	// DEBUG
		//	System.err.println(String.format("WARNING: %s \"%s\" is probably a bad token. It was ignored", node.getNodeType(), name));
		//	return null;
		//}

		return token;
	}

	public void addReference(AstNode node, Entry<?> deobfEntry, Entry<?> deobfContext) {
		Token token = getToken(node);
		if (token != null) {
			EntryReference<Entry<?>, Entry<?>> deobfReference = new EntryReference<>(deobfEntry, token.text, deobfContext);
			this.tokenToReference.put(token, deobfReference);
			this.referenceToTokens.put(deobfReference, token);
		}
	}

	public void addDeclaration(AstNode node, Entry<?> deobfEntry) {
		Token token = getToken(node);
		if (token != null) {
			EntryReference<Entry<?>, Entry<?>> reference = new EntryReference<>(deobfEntry, token.text);
			this.tokenToReference.put(token, reference);
			this.referenceToTokens.put(reference, token);
			this.declarationToToken.put(deobfEntry, token);
		}
	}

	public Token getReferenceToken(int pos) {
		Token token = this.tokenToReference.floorKey(new Token(pos, pos, null));
		if (token != null && token.contains(pos)) {
			return token;
		}
		return null;
	}

	public Collection<Token> getReferenceTokens(EntryReference<Entry<?>, Entry<?>> deobfReference) {
		return this.referenceToTokens.get(deobfReference);
	}

	@Nullable
	public EntryReference<Entry<?>, Entry<?>> getReference(Token token) {
		if (token == null) {
			return null;
		}
		return this.tokenToReference.get(token);
	}

	public Iterable<Token> referenceTokens() {
		return this.tokenToReference.keySet();
	}

	public Iterable<Token> declarationTokens() {
		return this.declarationToToken.values();
	}

	public Iterable<Entry<?>> declarations() {
		return this.declarationToToken.keySet();
	}

	public Token getDeclarationToken(Entry<?> entry) {
		return this.declarationToToken.get(entry);
	}

	public int getLineNumber(int pos) {
		// line number is 1-based
		int line = 0;
		for (Integer offset : this.lineOffsets) {
			if (offset > pos) {
				break;
			}
			line++;
		}
		return line;
	}

	public int getColumnNumber(int pos) {
		// column number is 1-based
		return pos - this.lineOffsets.get(getLineNumber(pos) - 1) + 1;
	}

	private int toPos(int line, int col) {
		// line and col are 1-based
		return this.lineOffsets.get(line - 1) + col - 1;
	}

	public void resolveReferences(EntryResolver resolver) {
		// resolve all the classes in the source references
		for (Token token : Lists.newArrayList(referenceToTokens.values())) {
			EntryReference<Entry<?>, Entry<?>> reference = tokenToReference.get(token);
			EntryReference<Entry<?>, Entry<?>> resolvedReference = resolver.resolveFirstReference(reference, ResolutionStrategy.RESOLVE_CLOSEST);

			// replace the reference
			tokenToReference.replace(token, resolvedReference);

			Collection<Token> tokens = referenceToTokens.removeAll(reference);
			referenceToTokens.putAll(resolvedReference, tokens);
		}
	}
}
