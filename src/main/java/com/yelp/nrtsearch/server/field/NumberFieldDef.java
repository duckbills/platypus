/*
 * Copyright 2020 Yelp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yelp.nrtsearch.server.field;

import static com.yelp.nrtsearch.server.analysis.AnalyzerCreator.hasAnalyzer;

import com.yelp.nrtsearch.server.doc.LoadedDocValues;
import com.yelp.nrtsearch.server.field.properties.Bindable;
import com.yelp.nrtsearch.server.field.properties.DocValueUpdatable;
import com.yelp.nrtsearch.server.field.properties.RangeQueryable;
import com.yelp.nrtsearch.server.field.properties.Sortable;
import com.yelp.nrtsearch.server.field.properties.TermQueryable;
import com.yelp.nrtsearch.server.grpc.FacetType;
import com.yelp.nrtsearch.server.grpc.Field;
import com.yelp.nrtsearch.server.grpc.SortType;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongToDoubleFunction;
import java.util.function.ToLongFunction;
import org.apache.lucene.document.Document;
import org.apache.lucene.facet.FacetField;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.DoubleValuesSource;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.util.NumericUtils;

/**
 * Base class for all fields that are a {@link Number} type. Contains the common handling used by
 * all of number fields and provides abstract functions for type specific operations.
 *
 * @param <T> doc value object type
 */
public abstract class NumberFieldDef<T> extends IndexableFieldDef<T>
    implements Bindable, Sortable, RangeQueryable, TermQueryable, DocValueUpdatable {
  public static final Function<String, Number> INT_PARSER = Integer::valueOf;
  public static final Function<String, Number> LONG_PARSER = Long::valueOf;
  public static final Function<String, Number> FLOAT_PARSER = Float::valueOf;
  public static final Function<String, Number> DOUBLE_PARSER = Double::valueOf;

  public static final ToLongFunction<Number> SORTED_FLOAT_ENCODER =
      value -> NumericUtils.floatToSortableInt(value.floatValue());
  public static final ToLongFunction<Number> SORTED_DOUBLE_ENCODER =
      value -> NumericUtils.doubleToSortableLong(value.doubleValue());

  public static final String LENGTH_BINDING_PROPERTY = "length";
  public static final String EMPTY_BINDING_PROPERTY = "empty";

  private final Function<String, Number> fieldParser;

  protected NumberFieldDef(
      String name,
      Field requestField,
      Function<String, Number> fieldParser,
      FieldDefCreator.FieldDefCreatorContext context,
      Class<T> docValuesClass) {
    super(name, requestField, context, docValuesClass);
    this.fieldParser = fieldParser;
  }

  protected void validateRequest(Field requestField) {
    super.validateRequest(requestField);

    if (hasAnalyzer(requestField)) {
      throw new IllegalArgumentException("no analyzer allowed on Number fields");
    }
  }

  protected DocValuesType parseDocValuesType(Field requestField) {
    if (requestField.getStoreDocValues()) {
      if (requestField.getMultiValued()) {
        return DocValuesType.SORTED_NUMERIC;
      } else {
        return DocValuesType.NUMERIC;
      }
    }
    return DocValuesType.NONE;
  }

  @Override
  protected FacetValueType parseFacetValueType(Field requestField) {
    FacetType facetType = requestField.getFacet();
    if (facetType.equals(FacetType.HIERARCHY)) {
      if (requestField.getStore()) {
        throw new IllegalArgumentException("facet=hierarchy fields cannot have store=true");
      }
      return FacetValueType.HIERARCHY;
    } else if (facetType.equals(FacetType.NUMERIC_RANGE)) {
      if (!requestField.getSearch()) {
        throw new IllegalArgumentException("facet=numericRange fields must have search=true");
      }
      return FacetValueType.NUMERIC_RANGE;
    } else if (facetType.equals(FacetType.SORTED_SET_DOC_VALUES)) {
      throw new IllegalArgumentException(
          "facet=SORTED_SET_DOC_VALUES can work only for TEXT fields");
    } else if (facetType.equals(FacetType.FLAT)) {
      return FacetValueType.FLAT;
    }
    return FacetValueType.NO_FACETS;
  }

  /**
   * Get the doc value {@link org.apache.lucene.document.Field} to add to document during indexing.
   * This field should be populated with the given value.
   *
   * @param fieldValue field doc value
   * @return lucene doc value field to index
   */
  protected abstract org.apache.lucene.document.Field getDocValueField(Number fieldValue);

  /**
   * Get the point {@link org.apache.lucene.document.Field} used to search numeric values. This
   * field should be populated with the given value.
   *
   * @param fieldValue field point value
   * @return lucene point field to index
   */
  protected abstract org.apache.lucene.document.Field getPointField(Number fieldValue);

  /**
   * Get the appropriate {@link LoadedDocValues} implementation for the field type using the given
   * {@link NumericDocValues} accessor.
   *
   * @param docValues doc values accessor
   * @return loaded doc values implementation
   */
  protected abstract LoadedDocValues<T> getNumericDocValues(NumericDocValues docValues);

  /**
   * Get the appropriate {@link LoadedDocValues} implementation for the field type using the given
   * {@link SortedNumericDocValues} accessor.
   *
   * @param docValues doc values accessor
   * @return loaded doc values implementation
   */
  protected abstract LoadedDocValues<T> getSortedNumericDocValues(SortedNumericDocValues docValues);

  /**
   * Get the {@link LongToDoubleFunction} to use when decoding doc value data in {@link
   * org.apache.lucene.expressions.Expression} bindings.
   *
   * @return decoder for doc value data to double
   */
  protected abstract LongToDoubleFunction getBindingDecoder();

  /**
   * Get the {@link SortField.Type} to use when building a {@link SortField}.
   *
   * @return sort field type for field
   */
  protected abstract SortField.Type getSortFieldType();

  /**
   * Get the value to use for missing data when sorting.
   *
   * @param missingLast if missing data should sort to last
   * @return sort missing value
   */
  protected abstract Number getSortMissingValue(boolean missingLast);

  /**
   * Convert the string to number
   *
   * @param numberString number string
   * @return number value of the string
   */
  protected Number parseNumberString(String numberString) {
    return fieldParser.apply(numberString);
  }

  @Override
  public void parseDocumentField(
      Document document, List<String> fieldValues, List<List<String>> facetHierarchyPaths) {
    if (fieldValues.size() > 1 && !isMultiValue()) {
      throw new IllegalArgumentException(
          "Cannot index multiple values into single value field: " + getName());
    }
    for (String fieldStr : fieldValues) {
      Number fieldValue = parseNumberString(fieldStr);
      if (hasDocValues()) {
        document.add(getDocValueField(fieldValue));
      }
      if (isSearchable()) {
        document.add(getPointField(fieldValue));
      }
      if (isStored()) {
        document.add(new FieldWithData(getName(), fieldType, fieldValue));
      }

      addFacet(document, fieldValue);
    }
  }

  private void addFacet(Document document, Number value) {
    if (facetValueType == FacetValueType.HIERARCHY || facetValueType == FacetValueType.FLAT) {
      String facetValue = value.toString();
      document.add(new FacetField(getName(), facetValue));
    }
  }

  @Override
  public LoadedDocValues<T> getDocValues(LeafReaderContext context) throws IOException {
    if (docValuesType == DocValuesType.NUMERIC) {
      NumericDocValues numericDocValues = DocValues.getNumeric(context.reader(), getName());
      return getNumericDocValues(numericDocValues);
    } else if (docValuesType == DocValuesType.SORTED_NUMERIC) {
      SortedNumericDocValues sortedNumericDocValues =
          DocValues.getSortedNumeric(context.reader(), getName());
      return getSortedNumericDocValues(sortedNumericDocValues);
    }
    throw new IllegalStateException(
        String.format("Unsupported doc value type %s for field %s", docValuesType, this.getName()));
  }

  @Override
  public DoubleValuesSource getExpressionBinding(String property) {
    if (!hasDocValues()) {
      throw new IllegalStateException("Cannot bind field without doc values enabled");
    }
    switch (property) {
      case VALUE_PROPERTY:
        if (isMultiValue()) {
          return new BindingValuesSources.SortedNumericMinValuesSource(
              getName(), getBindingDecoder());
        } else {
          return new BindingValuesSources.NumericDecodedValuesSource(
              getName(), getBindingDecoder());
        }
      case LENGTH_BINDING_PROPERTY:
        if (isMultiValue()) {
          return new BindingValuesSources.SortedNumericLengthValuesSource(getName());
        } else {
          return new BindingValuesSources.NumericLengthValuesSource(getName());
        }
      case EMPTY_BINDING_PROPERTY:
        if (isMultiValue()) {
          return new BindingValuesSources.SortedNumericEmptyValuesSource(getName());
        } else {
          return new BindingValuesSources.NumericEmptyValuesSource(getName());
        }
      default:
        throw new IllegalArgumentException("Unsupported expression binding property: " + property);
    }
  }

  @Override
  public SortField getSortField(SortType type) {
    verifyDocValues("Sort field");
    SortField sortField =
        new SortedNumericSortField(
            getName(),
            getSortFieldType(),
            type.getReverse(),
            NUMERIC_TYPE_PARSER.apply(type.getSelector()));

    boolean missingLast = type.getMissingLast();
    sortField.setMissingValue(getSortMissingValue(missingLast));
    return sortField;
  }

  @Override
  public boolean isUpdatable() {
    if (isSearchable() || isMultiValue() || !hasDocValues()) {
      return false;
    }
    return true;
  }

  @Override
  public org.apache.lucene.document.Field getUpdatableDocValueField(List<String> val) {
    if (val.size() > 1) {
      throw new IllegalArgumentException(
          "Cannot update multiple value field with docValueUpdate API");
    }
    return getDocValueField(parseNumberString(val.get(0)));
  }
}
