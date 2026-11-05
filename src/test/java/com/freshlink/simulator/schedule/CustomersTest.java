package com.freshlink.simulator.schedule;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomersTest {

    @Test
    void sub와_단골_점포를_읽는다() {
        Customers c = Customers.parse(" a-1:4,7 ; b-2:5 ;; broken ; c-3: ");

        assertThat(c.all()).containsExactly(
                new Customers.Customer("a-1", List.of(4L, 7L)),
                new Customers.Customer("b-2", List.of(5L)));
        assertThat(c.of(7)).extracting(Customers.Customer::sub).containsExactly("a-1");
        assertThat(c.stores()).containsExactly(4L, 5L, 7L);
    }

    @Test
    void 비어_있으면_고객이_없다() {
        assertThat(Customers.parse("").all()).isEmpty();
        assertThat(Customers.parse(null).all()).isEmpty();
    }
}
