#include <stdio.h>
#include <stdint.h>
#include "rocc.h"

#define INPUT_ELEMS 1024

#define FUNCT_CONFIG  0
#define FUNCT_LOAD    1
#define FUNCT_COMPUTE 2
#define FUNCT_STORE   3

#define ROCC_X 0

static int16_t input_buf [INPUT_ELEMS] __attribute__((aligned(64)));
static int16_t kernel_buf[32]          __attribute__((aligned(64)));
static int16_t hw_output [INPUT_ELEMS] __attribute__((aligned(64)));

static inline uint64_t conv_config(uint64_t kernel_size, void *output_addr) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(ROCC_X, ret,
                         kernel_size, (uint64_t)output_addr,
                         FUNCT_CONFIG);
    return ret;
}

static inline uint64_t conv_load(void *input_addr, void *kernel_addr) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(ROCC_X, ret,
                         (uint64_t)input_addr, (uint64_t)kernel_addr,
                         FUNCT_LOAD);
    return ret;
}

static inline uint64_t conv_compute(void) {
    uint64_t ret;
    ROCC_INSTRUCTION_D(ROCC_X, ret, FUNCT_COMPUTE);
    return ret;
}

static inline uint64_t conv_store(void) {
    uint64_t ret;
    ROCC_INSTRUCTION_D(ROCC_X, ret, FUNCT_STORE);
    return ret;
}

static inline uint64_t pack4(uint16_t a, uint16_t b, uint16_t c, uint16_t d) {
    return ((uint64_t)d << 48) |
           ((uint64_t)c << 32) |
           ((uint64_t)b << 16) |
           ((uint64_t)a);
}

static void init_data(void) {
    volatile uint64_t *in64  = (volatile uint64_t *)input_buf;
    volatile uint64_t *out64 = (volatile uint64_t *)hw_output;
    volatile uint64_t *ker64 = (volatile uint64_t *)kernel_buf;

    for (int i = 0; i < INPUT_ELEMS / 4; i++) {
        uint16_t a = (uint16_t)(((4 * i + 1) % 63 + 1) << 8);
        uint16_t b = (uint16_t)(((4 * i + 2) % 63 + 1) << 8);
        uint16_t c = (uint16_t)(((4 * i + 3) % 63 + 1) << 8);
        uint16_t d = (uint16_t)(((4 * i + 4) % 63 + 1) << 8);

        // Use 64-bit stores to avoid CPU-generated PutPartial.
        in64[i] = pack4(a, b, c, d);
        out64[i] = 0x7e7e7e7e7e7e7e7eULL;
    }

    for (int i = 0; i < 4; i++) {
        // Use 64-bit stores to clear the kernel buffer.
        ker64[i] = 0;
    }

    // kernel[0] = 1.0 in 8.8 fixed-point format.
    ker64[0] = 0x0000000000000100ULL;
}

static int check_selected_outputs(void) {
    int indices[] = {0, 1, 2, 3, 32, 704, 832, 1020, 1021, 1022, 1023};
    int n = sizeof(indices) / sizeof(indices[0]);
    int pass = 1;

    for (int k = 0; k < n; k++) {
        int i = indices[k];
        int16_t hw = hw_output[i];
        int16_t expected = input_buf[i];

        printf("[CHECK] i=%d hw=0x%04x expected=0x%04x\n",
               i,
               (uint16_t)hw,
               (uint16_t)expected);

        if (hw != expected) {
            printf("[MISMATCH] i=%d hw=0x%04x expected=0x%04x\n",
                   i,
                   (uint16_t)hw,
                   (uint16_t)expected);
            pass = 0;
        }
    }

    return pass;
}

int main(void) {
    uint64_t ret;

    printf("=== conv_debug_fast ===\n");

    init_data();

    printf("[ADDR] input=%p kernel=%p output=%p\n",
           input_buf, kernel_buf, hw_output);

    ret = conv_config(1, hw_output);
    printf("[ROCC] CONFIG ret=%lu\n", (unsigned long)ret);

    ret = conv_load(input_buf, kernel_buf);
    printf("[ROCC] LOAD ret=%lu\n", (unsigned long)ret);

    ret = conv_compute();
    printf("[ROCC] COMPUTE ret=%lu\n", (unsigned long)ret);

    ret = conv_store();
    printf("[ROCC] STORE ret=%lu\n", (unsigned long)ret);

    asm volatile("fence rw, rw" ::: "memory");

    if (!check_selected_outputs()) {
        printf("[TEST] FAIL\n");
        return 1;
    }

    printf("[TEST] PASS\n");
    return 0;
}
