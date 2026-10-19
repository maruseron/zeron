# References and mutation

An array is an object that can be referred to by a name. Assigning that reference to another
name does not make a second array; both names can refer to the same one. This is called sharing
or aliasing:

```zeron
let values: &Array<Int> = [10, 20, 30];
let sameValues: &Array<Int> = values;
```

Now `values` and `sameValues` refer to the same array. If the array is changed through one name,
the change is visible when it is read through the other name:

```zeron
values[0] = 99;
let first = sameValues[0];
```

After the assignment, `first` is `99`. No array was copied when `sameValues` was created.

## Permission to change an array

The `&` before a type means that code using this reference is allowed to change the referenced
value. For example, `&Array<Int>` allows writing to array slots. Without `&`, `Array<Int>` can
read array elements but cannot replace them:

```zeron
let values: &Array<Int> = [10, 20, 30];
let readable: Array<Int> = values;

values[0] = 99;           // allowed
let first = readable[0];  // allowed; first is 99
```

`readable` and `values` still refer to the same array. The difference is permission: code using
`readable` may read an element, but cannot write one. A mutable reference can be used where a
read-only reference is expected. The reverse is not allowed: a read-only reference does not
carry permission to change the array, so it cannot be made mutable simply by adding `&`.

This permission is separate from whether the name itself can be reassigned:

```zeron
let mut current: Array<Int> = [1, 2, 3];
current = [4, 5, 6];       // the name can refer to another array
```

Here `let mut` allows `current` to be assigned a different array, but the type `Array<Int>` still
does not allow changing an element in either array. Conversely, `let values: &Array<Int>` would
make the name fixed while still allowing changes to the array's slots. In short, `let mut`
controls whether the name can be reassigned; `&` controls whether the referenced array can be
changed through that name.

## Shared references are not exclusive

The `&` marker grants permission to mutate; it does not mean that only one reference to the array
exists. Other names may refer to the same array, and they may observe changes made through a
mutable reference. A read-only reference therefore means that code using that particular reference
cannot write—not that the array is guaranteed never to change.

Zeron does not promise exclusive access or provide a borrow checker. Do not assume that `&Array<T>`
is the only reference to its array, or that a read-only reference prevents some other reference
from changing it. The same permission distinction applies to class instances and to methods marked
`mut`; classes and methods are introduced in
[classes and constructors](09-classes-and-constructors.md).
