// =============================================================================
// conv_fixed_float_dump.c
// CLEAN_SIMPLE_POINT_TEST_V3_NO_FULL_SCAN
//
// ELEN90093 CNN Accelerator - Simple Fixed16 + Float16 5x5 Point Check
//
// This file intentionally does NOT contain:
//   - FIRST MISMATCH
//   - PASS - all
//   - full 1024-output scan
//   - first-20-output scan
//
// Tests:
//   1) fixed16 5x5 no-bias
//   2) float16 5x5 no-bias
//
// Input:
//   X[row][col] = row + col
//
// Kernel:
//   5x5 all 1.0
//
// Padding:
//   zero padding
// =============================================================================

#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "rocc.h"

#define INPUT_SIZE   32
#define INPUT_ELEMS  (INPUT_SIZE * INPUT_SIZE)

#define FUNCT_CONFIG   0
#define FUNCT_DATA     1
#define FUNCT_COMPUTE  2
#define FUNCT_STORE    3
#define FUNCT_KERNEL   4

#define DATA_FIXED16   0
#define DATA_FLOAT16   1

#define ROCC_X 0

#define CONFIG_FLAGS(data_type, bias_enable) \
    (((uint64_t)(data_type) & 0x3ULL) | ((((uint64_t)(bias_enable)) & 0x1ULL) << 2))

#define FIXED_ONE    ((uint16_t)0x0100)
#define FLOAT16_ONE  ((uint16_t)0x3c00)
#define ZERO16       ((uint16_t)0x0000)

static uint16_t input_buf[INPUT_ELEMS]   __attribute__((aligned(64)));
static uint16_t kernel_buf[25]           __attribute__((aligned(64)));
static uint16_t output_buf[INPUT_ELEMS]  __attribute__((aligned(64)));
static uint16_t bias_buf[1]              __attribute__((aligned(64)));

typedef struct {
    uint8_t row;
    uint8_t col;
    uint16_t expect_fixed;
    uint16_t expect_float;
    const char *label;
} TestPoint;

// Expected values for input X[row][col] = row + col, 5x5 all-one kernel.
static const TestPoint points[] = {
    {  0,  0, 0x1200, 0x4c80, "top-left padding, sum 18" },
    {  0, 16, 0xff00, 0x5bf8, "top edge padding, sum 255" },
    {  2,  2, 0x6400, 0x5640, "first full window, sum 100" },
    { 16, 16, 0x2000, 0x6240, "centre full window, sum 800" },
    { 29, 29, 0xaa00, 0x65aa, "last full window, sum 1450" },
    { 31, 31, 0x1c00, 0x6038, "bottom-right padding, sum 540" },
};

#define NUM_POINTS ((int)(sizeof(points) / sizeof(points[0])))

static inline uint64_t read_cycle(void) {
    uint64_t cycle;
    asm volatile("rdcycle %0" : "=r"(cycle));
    return cycle;
}

static inline uint64_t conv_config(uint64_t kernel_size, uint64_t config_flags) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(ROCC_X, ret, kernel_size, config_flags, FUNCT_CONFIG);
    return ret;
}

static inline uint64_t conv_kernel(void *kernel_addr, void *bias_addr) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(ROCC_X, ret,
                         (uint64_t)kernel_addr,
                         (uint64_t)bias_addr,
                         FUNCT_KERNEL);
    return ret;
}

static inline uint64_t conv_data(void *input_addr, void *output_addr) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(ROCC_X, ret,
                         (uint64_t)input_addr,
                         (uint64_t)output_addr,
                         FUNCT_DATA);
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

// Convert a small unsigned integer to raw IEEE-754 half bits.
// Current test values are within a small exact integer range.
static uint16_t uint_to_float16_bits(unsigned int value) {
    if (value == 0) {
        return 0;
    }

    unsigned int exp = 0;
    unsigned int tmp = value;

    while (tmp > 1) {
        tmp >>= 1;
        exp++;
    }

    unsigned int half_exp = exp + 15;
    unsigned int base = 1u << exp;
    unsigned int frac_value = value - base;
    unsigned int half_frac;

    if (exp <= 10) {
        half_frac = frac_value << (10u - exp);
    } else {
        half_frac = frac_value >> (exp - 10u);
    }

    return (uint16_t)((half_exp << 10) | (half_frac & 0x03ffu));
}

static void init_fixed_case(void) {
    memset(output_buf, 0, sizeof(output_buf));
    bias_buf[0] = ZERO16;

    for (int row = 0; row < INPUT_SIZE; row++) {
        for (int col = 0; col < INPUT_SIZE; col++) {
            input_buf[row * INPUT_SIZE + col] =
                (uint16_t)(((row + col) << 8) & 0xffff);
        }
    }

    for (int i = 0; i < 25; i++) {
        kernel_buf[i] = FIXED_ONE;
    }
}

static void init_float_case(void) {
    memset(output_buf, 0, sizeof(output_buf));
    bias_buf[0] = ZERO16;

    for (int row = 0; row < INPUT_SIZE; row++) {
        for (int col = 0; col < INPUT_SIZE; col++) {
            input_buf[row * INPUT_SIZE + col] =
                uint_to_float16_bits((unsigned int)(row + col));
        }
    }

    for (int i = 0; i < 25; i++) {
        kernel_buf[i] = FLOAT16_ONE;
    }
}

static int run_hw_5x5(uint64_t data_type, const char *name, uint64_t *cycles) {
    uint64_t ret;

    asm volatile("fence rw, rw" ::: "memory");
    uint64_t t0 = read_cycle();

    ret = conv_config(5, CONFIG_FLAGS(data_type, 0));
    if (ret == 0) {
        printf("[%s] CONFIG error\n", name);
        return 0;
    }

    asm volatile("fence rw, rw" ::: "memory");

    ret = conv_kernel(kernel_buf, bias_buf);
    if (ret == 0) {
        printf("[%s] KERNEL error\n", name);
        return 0;
    }

    asm volatile("fence rw, rw" ::: "memory");

    ret = conv_data(input_buf, output_buf);
    if (ret == 0) {
        printf("[%s] DATA error\n", name);
        return 0;
    }

    asm volatile("fence rw, rw" ::: "memory");

    ret = conv_compute();
    if (ret == 0) {
        printf("[%s] COMPUTE error\n", name);
        return 0;
    }

    asm volatile("fence rw, rw" ::: "memory");

    ret = conv_store();
    if (ret == 0) {
        printf("[%s] STORE error\n", name);
        return 0;
    }

    asm volatile("fence rw, rw" ::: "memory");

    uint64_t t1 = read_cycle();
    *cycles = t1 - t0;

    return 1;
}

static int check_points_fixed(void) {
    int pass = 1;

    printf("[Fixed16_5x5] CLEAN V3 point check: %d points\n", NUM_POINTS);
    printf("  pixel   | expected | hardware | result | note\n");
    printf("  --------|----------|----------|--------|------------------------------\n");

    for (int i = 0; i < NUM_POINTS; i++) {
        int idx = points[i].row * INPUT_SIZE + points[i].col;
        uint16_t expected = points[i].expect_fixed;
        uint16_t hw = output_buf[idx];
        int ok = (expected == hw);

        if (!ok) {
            pass = 0;
        }

        printf("  [%2d,%2d] | 0x%04x   | 0x%04x   | %-4s   | %s\n",
               points[i].row,
               points[i].col,
               expected,
               hw,
               ok ? "PASS" : "FAIL",
               points[i].label);
    }

    printf("[Fixed16_5x5] CLEAN V3 result: %s\n\n", pass ? "PASS" : "FAIL");
    return pass;
}

static int check_points_float(void) {
    int pass = 1;

    printf("[Float16_5x5] CLEAN V3 point check: %d points\n", NUM_POINTS);
    printf("  pixel   | expected | hardware | result | note\n");
    printf("  --------|----------|----------|--------|------------------------------\n");

    for (int i = 0; i < NUM_POINTS; i++) {
        int idx = points[i].row * INPUT_SIZE + points[i].col;
        uint16_t expected = points[i].expect_float;
        uint16_t hw = output_buf[idx];
        int ok = (expected == hw);

        if (!ok) {
            pass = 0;
        }

        printf("  [%2d,%2d] | 0x%04x   | 0x%04x   | %-4s   | %s\n",
               points[i].row,
               points[i].col,
               expected,
               hw,
               ok ? "PASS" : "FAIL",
               points[i].label);
    }

    printf("[Float16_5x5] CLEAN V3 result: %s\n\n", pass ? "PASS" : "FAIL");
    return pass;
}

static int run_fixed_5x5(void) {
    uint64_t cycles = 0;

    printf("\n=== Fixed16 5x5 CLEAN V3 simple point check ===\n");
    init_fixed_case();

    if (!run_hw_5x5(DATA_FIXED16, "Fixed16_5x5", &cycles)) {
        return 0;
    }

    printf("[Fixed16_5x5] HW cycles: %lu\n", (unsigned long)cycles);
    return check_points_fixed();
}

static int run_float_5x5(void) {
    uint64_t cycles = 0;

    printf("\n=== Float16 5x5 CLEAN V3 simple point check ===\n");
    init_float_case();

    if (!run_hw_5x5(DATA_FLOAT16, "Float16_5x5", &cycles)) {
        return 0;
    }

    printf("[Float16_5x5] HW cycles: %lu\n", (unsigned long)cycles);
    return check_points_float();
}

int main(void) {
    printf("=== CLEAN_SIMPLE_POINT_TEST_V3_NO_FULL_SCAN ===\n");
    printf("No software convolution loop. Only selected analytical points are checked.\n");

    int fixed_pass = run_fixed_5x5();
    int float_pass = run_float_5x5();

    printf("\n=== Final Result CLEAN V3 ===\n");
    printf("Fixed16_5x5: %s\n", fixed_pass ? "PASS" : "FAIL");
    printf("Float16_5x5: %s\n", float_pass ? "PASS" : "FAIL");

    return (fixed_pass && float_pass) ? 0 : 1;
}
