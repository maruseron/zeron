# 11. Packages, namespaces, and imports

As a program grows, its declarations need a clear home. Packages group related source files and
give their declarations longer, distinct names. Imports provide convenient shorter names in files
that use those declarations. Namespaces group related functions and values within a package.

## Packages and source files

Each source file can begin with a package declaration. For example, a file in a geometry package
can start like this:

```zeron
package geometry;

public class Point {
    x: Int;
    y: Int;
}
```

The package name is part of the declaration's full name, so `geometry.Point` is distinct from a
`Point` declared in another package. Files that declare the same package can use one another's
package-visible declarations; to make a declaration available outside that package, mark it
`public`.

In a project build, Zeron enforces that a source file's folders below a configured source root
match its package. For example, with `src` as a source root, a file declaring `package geometry;`
must be somewhere under `src/geometry/`. A file in the root of `src` uses the default package by
omitting a package declaration. The file name itself does not have to match a class name. When
compiling individual files outside a project build, this folder rule does not apply.

Some naming styles are conventions rather than rules the compiler enforces. Package names are
written as single lowercase words, such as `geometry` or `text`; use separate package components
for larger groupings. Source file names use **snake_case**, with lowercase words separated by
underscores, such as `point_tools.zn`.

## Imports

An import lets a file use a public declaration by its short name instead of writing its full
package name each time. For example, a file in package `app` can import `Point`:

```zeron
package app;

import geometry.Point;

fn origin(): Point = Point.new(0, 0);
```

Imports apply only to the file that contains them. You can also use a fully qualified name directly
without importing it, such as `geometry.Point`.

### Aliased imports

If two packages contain declarations with the same short name, or if a shorter name would be
clearer in one file, give an import an alias with `as`:

```zeron
import geometry.Point as GeoPoint;

fn origin(): GeoPoint = GeoPoint.new(0, 0);
```

The alias is the name this file uses. It does not rename the declaration in its package.

### Star imports and name collisions

A star import brings public declarations from one package into the file's short-name lookup:

```zeron
import geometry.*;
```

It does not include declarations from subpackages, and it does not re-export those declarations to
other files. Explicit imports take precedence over star imports. If two star imports provide the
same short name and the file uses that name, Zeron reports an ambiguity. Import the desired
declaration explicitly or give it an alias to make the choice clear:

```zeron
import geometry.Point as GeoPoint;
import mapping.Point as MapPoint;
```

An alias is also useful when a short name is already used for something else in the same file.

## Namespaces

A namespace groups related functions and immutable values under a qualified name. Unlike a class,
it does not describe objects and cannot be constructed. It is useful for operations that belong
together but do not need to act on one particular object's fields.

Namespace names use **PascalCase**, like the types they commonly support. The receiver type in an
extension declaration is also written in PascalCase; for example, `namespace Point` and
`extension Point`. Names of functions and extension methods use camelCase.

For example, a `Point` class can describe point objects, while a namespace with the same name can
hold functions for making common points:

```zeron
package geometry;

public class Point {
    x: Int;
    y: Int;
}

namespace Point {
    public fn origin(): Point = Point.new(0, 0);
}
```

Another file can call `geometry.Point.origin()` or import that function directly. The class and
namespace share a name but have different roles: `Point.new(...)` constructs an object, while
`Point.origin()` calls a namespace function.

It is a common Zeron convention to give a namespace the same name as the client class or contract
it is associated with. This keeps related helper functions near the name people already use for
that type, without making those helpers instance methods or requiring an object. For example, a
namespace named after a contract can hold functions that operate on any value conforming to that
contract.

Namespaces are package-scoped rather than file-scoped: files in the same package can add members
to the same namespace. This is handy for splitting related functions across files. A namespace
does not change package visibility rules; make a member `public` if code in other packages should
use it.

Project source roots and dependencies are configured in the manifest; see
[configuration and releases](25-configuration-releases-and-roadmap.md). For extension imports,
which make receiver-style operations available, see [extensions](12-extensions.md).
