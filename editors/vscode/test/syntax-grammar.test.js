const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const grammarPath = path.join(__dirname, '..', 'syntaxes', 'zeron.tmLanguage.json');
const grammar = JSON.parse(fs.readFileSync(grammarPath, 'utf8'));
const projectionPattern = grammar.repository['contextual-keywords'].patterns
  .find(pattern => pattern.match.includes('(out)'));
const forExpressionPattern = grammar.repository['for-expression'];
const handlerDeclarationPattern = grammar.repository.declarations.patterns
  .find(pattern => pattern.match.includes('(handler)'));
const dataClassPattern = grammar.repository.declarations.patterns
  .find(pattern => pattern.match.includes('(data)') && pattern.match.includes('(class)'));
const dataClassInitializerPattern = grammar.repository.declarations.patterns
  .find(pattern => pattern.match.includes('(raises)'));

test('highlights out only in generic type-argument positions', () => {
  const matcher = new RegExp(projectionPattern.match, 'g');
  const source = 'Box<out Animal, out Int>';
  const matches = Array.from(source.matchAll(matcher), match => match[2]);

  assert.deepEqual(matches, ['out', 'out']);
  assert.equal(matcher.test('let out = 1;'), false);
});

test('keeps in highlighted as a language keyword', () => {
  assert.ok(grammar.repository.keywords.match.includes('|in|'));
});

test('highlights yield and collect as for-expression result clauses', () => {
  assert.ok(forExpressionPattern);
  const begin = new RegExp(forExpressionPattern.begin);
  const end = new RegExp(forExpressionPattern.end, 'g');

  assert.ok(begin.test('for item in items'));
  assert.equal(begin.test('for (let item in items)'), false);
  assert.deepEqual(
    Array.from('yield item; collect item into sink;'.matchAll(end), match => match[1]),
    ['yield', 'collect']
  );
  assert.equal(grammar.repository.keywords.match.includes('|yield|'), false);
  assert.equal(grammar.repository.keywords.match.includes('|collect|'), false);
});

test('highlights nullable only as a generic admissibility marker', () => {
  const markerPattern = grammar.repository['contextual-keywords'].patterns
    .find(pattern => pattern.match.includes('(nullable)'));
  assert.ok(markerPattern);

  const matcher = new RegExp(markerPattern.match, 'g');
  const source = 'fn read<T: nullable + Display<T>, E: effect>(value: T): T = value;';
  assert.deepEqual(Array.from(source.matchAll(matcher), match => match[1]), ['nullable']);

  const ordinaryUses = 'let nullable = true; fn read(nullable: Int): Int = 1;';
  assert.equal(Array.from(ordinaryUses.matchAll(matcher)).length, 0);
});

test('keeps effect recognized in declaration and generic constraint syntax', () => {
  assert.ok(grammar.repository.keywords.match.includes('|effect|'));
  assert.ok(grammar.repository.declarations.patterns.some(pattern => pattern.match.includes('effect')));
});

test('highlights data class declarations and initializer headers', () => {
  assert.ok(dataClassPattern);
  const dataClass = new RegExp(dataClassPattern.match).exec('public data class Point');
  assert.ok(dataClass);
  assert.equal(dataClass[1], 'data');
  assert.equal(dataClass[3], 'class');
  assert.equal(dataClass[5], 'Point');

  assert.ok(dataClassInitializerPattern);
  const initializer = new RegExp(dataClassInitializerPattern.match)
    .exec('Point raises Failure {');
  assert.ok(initializer);
  assert.equal(initializer[1], 'Point');
  assert.equal(initializer[3], 'raises');
});

test('highlights named handlers, anonymous handler expressions, and handler types', () => {
  assert.ok(handlerDeclarationPattern);
  const declarationMatcher = new RegExp(handlerDeclarationPattern.match);
  const declaration = declarationMatcher.exec('public handler Named {');
  assert.ok(declaration);
  assert.equal(declaration[1], 'handler');
  assert.equal(declaration[2], 'Named');
  assert.equal(handlerDeclarationPattern.captures['1'].name, 'keyword.declaration.handler.zeron');
  assert.equal(handlerDeclarationPattern.captures['2'].name, 'entity.name.constant.zeron');

  const anonymousHandlerPattern = grammar.repository['contextual-keywords'].patterns
    .find(pattern => pattern.match.includes('handler'));
  assert.ok(anonymousHandlerPattern);
  assert.ok(new RegExp(anonymousHandlerPattern.match).test('handler { case Failure(_) -> ""; }'));

  const operatorPattern = new RegExp(grammar.repository.operators.match, 'g');
  assert.deepEqual(
    Array.from('String ~> {Failure} raises {}'.matchAll(operatorPattern), match => match[0])
      .filter(operator => operator === '~>'),
    ['~>']
  );
  assert.ok(grammar.repository.keywords.match.includes('|raises|'));
});

test('highlights implementation only before HKT implementation blocks', () => {
  const implementationPattern = grammar.repository['contextual-keywords'].patterns
    .find(pattern => pattern.match.includes('(implementation)'));
  assert.ok(implementationPattern);

  const matcher = new RegExp(implementationPattern.match);
  assert.ok(matcher.test('implementation Functor<Box<_>> {'));
  assert.equal(matcher.test('let implementation = 1;'), false);
});

test('highlights delegating only before witness and HKT implementation declarations', () => {
  const delegatingPattern = grammar.repository['contextual-keywords'].patterns
    .find(pattern => pattern.match.includes('delegating'));
  assert.ok(delegatingPattern);

  const matcher = new RegExp(delegatingPattern.match);
  assert.ok(matcher.test('delegating witness Render<Box> for Box {}'));
  assert.ok(matcher.test('delegating implementation Functor<Box<_>> {}'));
  assert.equal(matcher.test('delegating implementation = 1;'), false);
  assert.equal(matcher.test('let delegating = 1;'), false);
});
