import ts from "typescript";
import { INTERNAL_CAPTURE_LOCALS_KEY } from "./capture.js";

const LOG_METHODS = new Set(["trace", "debug", "info", "warn", "error", "fatal"]);

interface ScopeState {
  names: Set<string>;
}

function createScope(): ScopeState {
  return { names: new Set<string>() };
}

function isChronoLoggerCall(node: ts.CallExpression): boolean {
  if (!ts.isPropertyAccessExpression(node.expression)) {
    return false;
  }
  return ts.isIdentifier(node.expression.expression)
    && node.expression.expression.text === "ChronoLogger"
    && LOG_METHODS.has(node.expression.name.text);
}

function isNamedCall(node: ts.CallExpression, name: string): boolean {
  return ts.isIdentifier(node.expression) && node.expression.text === name;
}

function addBindingName(name: ts.BindingName, scope: ScopeState): void {
  if (ts.isIdentifier(name)) {
    scope.names.add(name.text);
    return;
  }
  name.elements.forEach((element) => {
    if (ts.isBindingElement(element)) {
      addBindingName(element.name, scope);
    }
  });
}

function scopeLocalsObject(scope: ScopeState): ts.Expression {
  const properties = [...scope.names]
    .filter((name) => name !== "undefined")
    .sort()
    .map((name) => ts.factory.createShorthandPropertyAssignment(name));
  return ts.factory.createObjectLiteralExpression(properties, false);
}

function buildMergedCaptureFields(
  existing: ts.Expression | undefined,
  scope: ScopeState,
): ts.Expression {
  const captureProperty = ts.factory.createPropertyAssignment(
    ts.factory.createStringLiteral(INTERNAL_CAPTURE_LOCALS_KEY),
    scopeLocalsObject(scope),
  );
  if (!existing) {
    return ts.factory.createObjectLiteralExpression([captureProperty], true);
  }
  return ts.factory.createCallExpression(
    ts.factory.createPropertyAccessExpression(ts.factory.createIdentifier("Object"), "assign"),
    undefined,
    [
      ts.factory.createObjectLiteralExpression(),
      existing,
      ts.factory.createObjectLiteralExpression([captureProperty], true),
    ],
  );
}

function buildMergedSpanOptions(
  existing: ts.Expression | undefined,
  scope: ScopeState,
): ts.Expression {
  const captureProperty = ts.factory.createPropertyAssignment(
    "captureLocals",
    scopeLocalsObject(scope),
  );
  if (!existing) {
    return ts.factory.createObjectLiteralExpression([captureProperty], true);
  }
  return ts.factory.createCallExpression(
    ts.factory.createPropertyAccessExpression(ts.factory.createIdentifier("Object"), "assign"),
    undefined,
    [
      ts.factory.createObjectLiteralExpression(),
      existing,
      ts.factory.createObjectLiteralExpression([captureProperty], true),
    ],
  );
}

export function instrumentSource(sourceText: string, fileName = "input.ts"): string {
  const sourceFile = ts.createSourceFile(fileName, sourceText, ts.ScriptTarget.ES2022, true, ts.ScriptKind.TS);
  const scopes: ScopeState[] = [createScope()];

  const transformer: ts.TransformerFactory<ts.SourceFile> = (context) => {
    const visit: ts.Visitor = (node) => {
      const currentScope = scopes[scopes.length - 1];

      if (ts.isVariableDeclaration(node)) {
        addBindingName(node.name, currentScope);
      } else if (ts.isParameter(node)) {
        addBindingName(node.name, currentScope);
      } else if (ts.isFunctionDeclaration(node) && node.name) {
        currentScope.names.add(node.name.text);
      } else if (ts.isCatchClause(node) && node.variableDeclaration) {
        addBindingName(node.variableDeclaration.name, currentScope);
      }

      if (ts.isFunctionLike(node)) {
        const nextScope = createScope();
        scopes.push(nextScope);
        const visited = ts.visitEachChild(node, visit, context);
        scopes.pop();
        return visited;
      }

      if (ts.isCallExpression(node)) {
        if (isChronoLoggerCall(node)) {
          const args = [...node.arguments];
          args[1] = buildMergedCaptureFields(args[1], currentScope);
          return ts.factory.updateCallExpression(node, node.expression, node.typeArguments, args);
        }
        if (isNamedCall(node, "withTrace") || isNamedCall(node, "withSpan")) {
          const args = [...node.arguments];
          args[2] = buildMergedSpanOptions(args[2], currentScope);
          return ts.factory.updateCallExpression(node, node.expression, node.typeArguments, args);
        }
        if (isNamedCall(node, "startSpan")) {
          const args = [...node.arguments];
          args[1] = buildMergedSpanOptions(args[1], currentScope);
          return ts.factory.updateCallExpression(node, node.expression, node.typeArguments, args);
        }
      }

      return ts.visitEachChild(node, visit, context);
    };

    return (node) => ts.visitNode(node, visit) as ts.SourceFile;
  };

  const result = ts.transform(sourceFile, [transformer]);
  try {
    const printer = ts.createPrinter({ newLine: ts.NewLineKind.LineFeed });
    return printer.printFile(result.transformed[0]);
  } finally {
    result.dispose();
  }
}
