# Export

ROBOT can export details about ontology entities as a table (TSV or CSV). At minimum, the `export` command expects an input ontology (`--input`), a set of columns (`--columns`), and a file to write to (`--export`):

```
robot export --input nucleus.owl \
  --columns "CURIE LABEL" \
  --export nucleus.csv
```

### Columns

The `--columns` are space-separated special keywords or properties used in the ontology. These should always be enclosed in double quotes.

* **Special Keywords**:
	* `IRI`: creates an "IRI" column based on the full unique identifier
	* `CURIE`: creates a "CURIE" column based on the short form of the unique identifier
	* `LABEL`: creates a "Label" column based on `rdfs:label`
	* `subclass-of`: creates a "SubClass Of" column based on `rdfs:subClassOf`
	* `equivalent-classes`: creates an "Equivalent Classes" column based on `owl:equivalentClass`
	* `subproperty-of`: creates a "SubProperty Of" column based on `rdfs:subPropertyOf`
	* `equivalent-properties`: creates an "Equivalent Properties" column based on `owl:equivalentProperty`
	* `disjoints`: creates a "Disjoint With" column based on `owl:disjointWith`
	* `types`: creates an "Instance Of" column based on `rdf:type` for named individuals
* **Property CURIES**: you can always reference a property by the short form of the unique identifier (e.g. `oboInOwl:hasDbXref`). ROBOT will attempt to find a label for a CURIE to set as the column header, but if the label is not defined in the ontology, the header will be the CURIE. Any prefix used [must be defined](global/prefixes).
* **Property Labels**: as long as a property label is defined in the input ontology, you can reference a property by label enclosed in single quotes (e.g. `database_cross_reference`). This label will also be used as the column header.

The first column in the `--columns` list is used to sort the rows of the export. You can change the column that is sorted on by including `--sort <property>`.

All special keyword columns will include both named OWL objects (named classes, properties, and individuals) and anonymous expressions (class expressions, property expressions). When using another object or data property, the values will include both individuals and class expressions (from subclass or equivalent statements) in Manchester syntax. When using an annotation property, the literal value will be returned.

### Including and Excluding Entities

By default, the export includes details on the classes and individuals in an ontology. You can choose to exclude either of these:
* `--exclude-classes true`: do not include any classes
* `--exclude-individuals true`: do not include any instances of classes

For example, to return the details of *individuals only*:

    robot export --input template.owl \
      --columns "CURIE LABEL types" \
      --exclude-classes true \
      --export results/individuals.csv

By default, the export *does not* include details on properties in the input ontology. You can choose to *include* properties:
* `--exclude-properties false`: include details on annotation, data, and object properties

All three of these cannot be used together, as no entities will be included in the export.

Finally, the export will include anonymous expressions (subclasses, equivalent classes, property expressions). If you only wish to include *named* entities, add `--exclude-anonymous true`:

    robot export --input nucleus.owl \
      --columns "LABEL subclass-of 'part of'" \
      --exclude-anonymous true \
      --export results/nucleus.csv

### Preparing the Ontology

When exporting details on classes using object or data properties, we recommend running [reason](/reason), [relax](/relax), and [reduce](/reduce) first. You can also create a subset of entities using [remove](/remove) or [filter](/filter).

---

## Error Messages

### Exclude All Error

`--exclude-classes`, `--exclude-individuals`, and `--exclude-properties` cannot all be true as there will be no entities to return details on. Note that classes and individuals aree *included* by default and properties are *excluded* by default.

### Invalid Column Error

A property cannot be resolved, usually meaning that the label cannot be resolved. Ensure that the property label is defined in the input ontology or the column name provided is one of the special keywords.