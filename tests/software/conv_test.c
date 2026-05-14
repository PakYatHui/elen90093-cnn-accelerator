// =============================================================================
// conv_test.c
// ELEN90093 CNN Convolution Accelerator - Functional Test
//
// Tests 1x1, 3x3, and 5x5 convolution by comparing accelerator output
// against a software reference implementation.
//
// Fixed-point format: 8.8 signed
// All matrices are 32x32 elements of int16_t.
//
// Minimal update for new command interface:
// CONFIG -> KERNEL -> DATA -> COMPUTE -> STORE
// =============================================================================

#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include "rocc.h"

// =============================================================================
// Hardware parameters
// =============================================================================
#define INPUT_SIZE   32
#define INPUT_ELEMS  (INPUT_SIZE * INPUT_SIZE)

// funct7 encodings
#define FUNCT_CONFIG  0
#define FUNCT_DATA    1
#define FUNCT_COMPUTE 2
#define FUNCT_STORE   3
#define FUNCT_KERNEL  4

// RoCC uses custom0 opcode
#define ROCC_X 0

// Data type encoding
#define DATA_FIXED16 0
#define DATA_FLOAT32 1

// CONFIG rs2 layout:
// bits [1:0] = data type
// bit  [2]   = bias enable
#define CONFIG_FLAGS(data_type, bias_enable) \
    (((uint64_t)(data_type) & 0x3ULL) | ((((uint64_t)(bias_enable)) & 0x1ULL) << 2))

// =============================================================================
// Fixed-point helpers
// =============================================================================
#define TO_FP(x)   ((int16_t)((x) * 256.0))
#define FROM_FP(x) ((double)(x) / 256.0)

// Forward declaration
static int check_output(const char *test_name);

// =============================================================================
// RoCC instruction wrappers
// =============================================================================

// CONFIG:
// rs1 = kernel_size
// rs2 = config flags: data type + bias enable
static inline uint64_t conv_config(uint64_t kernel_size, uint64_t config_flags) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(ROCC_X, ret,
                         kernel_size, config_flags,
                         FUNCT_CONFIG);
    return ret;
}

// KERNEL:
// rs1 = kernel weight address
// rs2 = bias address
static inline uint64_t conv_kernel(void *kernel_addr, void *bias_addr) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(ROCC_X, ret,
                         (uint64_t)kernel_addr, (uint64_t)bias_addr,
                         FUNCT_KERNEL);
    return ret;
}

// DATA:
// rs1 = input data address
// rs2 = output data address
// This replaces the old LOAD command at software-interface level.
static inline uint64_t conv_data(void *input_addr, void *output_addr) {
    uint64_t ret;
    ROCC_INSTRUCTION_DSS(ROCC_X, ret,
                         (uint64_t)input_addr, (uint64_t)output_addr,
                         FUNCT_DATA);
    return ret;
}

// COMPUTE:
// no operands
static inline uint64_t conv_compute(void) {
    uint64_t ret;
    ROCC_INSTRUCTION_D(ROCC_X, ret, FUNCT_COMPUTE);
    return ret;
}

// STORE:
// no operands
static inline uint64_t conv_store(void) {
    uint64_t ret;
    ROCC_INSTRUCTION_D(ROCC_X, ret, FUNCT_STORE);
    return ret;
}

// =============================================================================
// Software golden reference
// =============================================================================
static void sw_conv(
    const int16_t *input,
    const int16_t *kernel,
    int16_t       *output,
    int            kernel_size)
{
    int radius = kernel_size / 2;

    for (int row = 0; row < INPUT_SIZE; row++) {
        for (int col = 0; col < INPUT_SIZE; col++) {
            int32_t sum = 0;

            for (int kr = 0; kr < kernel_size; kr++) {
                for (int kc = 0; kc < kernel_size; kc++) {
                    int in_row = row + kr - radius;
                    int in_col = col + kc - radius;

                    int16_t in_val = 0;
                    if (in_row >= 0 && in_row < INPUT_SIZE &&
                        in_col >= 0 && in_col < INPUT_SIZE) {
                        in_val = input[in_row * INPUT_SIZE + in_col];
                    }

                    int16_t ker_val = kernel[kr * kernel_size + kc];

                    // 8.8 x 8.8 produces 16.16.
                    // Shift right by 8 to return to 8.8 scale.
                    sum += ((int32_t)in_val * (int32_t)ker_val) >> 8;
                }
            }

            // Match the hardware truncation behaviour.
            output[row * INPUT_SIZE + col] = (int16_t)(sum & 0xFFFF);
        }
    }
}

// =============================================================================
// Test data buffers
// =============================================================================
static int16_t input_buf [INPUT_ELEMS] __attribute__((aligned(64)));
static int16_t kernel_buf[5 * 5]       __attribute__((aligned(64)));
static int16_t hw_output [INPUT_ELEMS] __attribute__((aligned(64)));
static int16_t sw_output [INPUT_ELEMS] __attribute__((aligned(64)));

static inline uint64_t read_cycle(void) {
    uint64_t cycle;
    asm volatile ("rdcycle %0" : "=r"(cycle));
    return cycle;
}

// =============================================================================
// Run one complete test
// =============================================================================
static int run_test(const char *name, int kernel_size) {
    memset(hw_output, 0, sizeof(hw_output));
    memset(sw_output, 0, sizeof(sw_output));

    printf("\n=== %s (kernel=%dx%d) ===\n", name, kernel_size, kernel_size);

    // Fill input: value at (row, col) = row + col.
    for (int row = 0; row < INPUT_SIZE; row++) {
        for (int col = 0; col < INPUT_SIZE; col++) {
            input_buf[row * INPUT_SIZE + col] = TO_FP(row + col);
        }
    }

    // Fill kernel: identity-like kernel.
    memset(kernel_buf, 0, sizeof(kernel_buf));

    if (kernel_size == 1) {
        kernel_buf[0] = TO_FP(1.0);
    } else {
        int centre = (kernel_size / 2) * kernel_size + (kernel_size / 2);
        kernel_buf[centre] = TO_FP(1.0);
    }

    uint64_t t0, t1;
    uint64_t ret;

    // Ensure CPU writes to input/kernel/output buffers are visible.
    asm volatile("fence rw, rw" ::: "memory");

    t0 = read_cycle();

    // CONFIG: fixed16, no bias.
    ret = conv_config((uint64_t)kernel_size,
                      CONFIG_FLAGS(DATA_FIXED16, 0));
    if (!ret) {
        printf("[%s] CONFIG failed\n", name);
        return 0;
    }

    asm volatile("fence rw, rw" ::: "memory");

    // KERNEL: kernel address + bias address.
    // Bias is disabled, so bias address is zero.
    ret = conv_kernel(kernel_buf, (void *)0);
    if (!ret) {
        printf("[%s] KERNEL failed\n", name);
        return 0;
    }

    asm volatile("fence rw, rw" ::: "memory");

    // DATA: input address + output address.
    // Internally this should trigger the existing sLoad logic.
    ret = conv_data(input_buf, hw_output);
    if (!ret) {
        printf("[%s] DATA failed\n", name);
        return 0;
    }

    ret = conv_compute();
    if (!ret) {
        printf("[%s] COMPUTE failed\n", name);
        return 0;
    }

    ret = conv_store();
    if (!ret) {
        printf("[%s] STORE failed\n", name);
        return 0;
    }

    t1 = read_cycle();

    uint64_t hw_cycles = t1 - t0;

    // Ensure CPU sees accelerator memory writes.
    asm volatile("fence rw, rw" ::: "memory");

    // Software reference.
    t0 = read_cycle();
    sw_conv(input_buf, kernel_buf, sw_output, kernel_size);
    t1 = read_cycle();

    uint64_t sw_cycles = t1 - t0;

    printf("  HW cycles: %lu\n", (unsigned long)hw_cycles);
    printf("  SW cycles: %lu\n", (unsigned long)sw_cycles);

    if (hw_cycles > 0) {
        printf("  Speedup:   %lu x\n", (unsigned long)(sw_cycles / hw_cycles));
    }

    return check_output(name);
}

// =============================================================================
// Compare hardware and software output
// =============================================================================
static int check_output(const char *test_name) {
    printf("[%s] First 8 outputs:\n", test_name);
    printf("  idx | sw_output        | hw_output\n");

    for (int i = 0; i < 9; i++) {
        printf("  [%d] | 0x%04x (%.4f) | 0x%04x (%.4f)\n",
               i,
               (uint16_t)sw_output[i], FROM_FP(sw_output[i]),
               (uint16_t)hw_output[i], FROM_FP(hw_output[i]));
    }

    int pass = 1;

    for (int i = 0; i < INPUT_ELEMS; i++) {
        if (hw_output[i] != sw_output[i]) {
            printf("[%s] MISMATCH at [%d][%d]: hw=0x%04x (%.4f) sw=0x%04x (%.4f)\n",
                   test_name,
                   i / INPUT_SIZE,
                   i % INPUT_SIZE,
                   (uint16_t)hw_output[i], FROM_FP(hw_output[i]),
                   (uint16_t)sw_output[i], FROM_FP(sw_output[i]));

            pass = 0;
            break;
        }
    }

    if (pass) {
        printf("[%s] PASS - all %d outputs match\n", test_name, INPUT_ELEMS);
    }

    return pass;
}

// =============================================================================
// Main
// =============================================================================
int main(void) {
    printf("=== ELEN90093 Convolution Accelerator Test ===\n");

    int all_pass = 1;

    all_pass &= run_test("Test1_1x1", 1);
    all_pass &= run_test("Test2_3x3", 3);
    all_pass &= run_test("Test3_5x5", 5);

    printf("\n=== Final Result: %s ===\n", all_pass ? "ALL PASS" : "SOME FAILED");

    return 0;
}