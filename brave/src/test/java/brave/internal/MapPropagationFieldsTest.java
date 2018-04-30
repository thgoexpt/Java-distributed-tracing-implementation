package brave.internal;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;

public class MapPropagationFieldsTest extends PropagationFieldsFactoryTest {
  @Override PropagationFieldsFactory newFactory() {
    return new PropagationFieldsFactory<MapPropagationFields>() {
      @Override protected Class<MapPropagationFields> type() {
        return MapPropagationFields.class;
      }

      @Override public MapPropagationFields create() {
        return new MapPropagationFields();
      }

      @Override protected MapPropagationFields create(MapPropagationFields parent) {
        return new MapPropagationFields(parent);
      }
    };
  }

  @Test public void put_allows_arbitrary_field() {
    MapPropagationFields fields = (MapPropagationFields) factory.create();

    fields.put("balloon-color", "red");

    assertThat(fields.values)
        .containsEntry("balloon-color", "red");
  }

  @Test public void unmodifiable() {
    MapPropagationFields fields = (MapPropagationFields) factory.create();

    fields.put(FIELD1, "a");

    try {
      fields.values.put(FIELD1, "b");
      failBecauseExceptionWasNotThrown(UnsupportedOperationException.class);
    } catch (UnsupportedOperationException e) {
    }
  }
}
