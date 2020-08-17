import { Span } from "opentracing";
import { reporter as gatsbyReporter } from "./reporter";
import { IStructuredError } from "../structured-errors/types";
interface ICreateProgressReporterArguments {
    id: string;
    text: string;
    start: number;
    total: number;
    span: Span;
    reporter: typeof gatsbyReporter;
}
export interface IProgressReporter {
    start(): void;
    setStatus(statusText: string): void;
    tick(increment?: number): void;
    panicOnBuild(arg: any, ...otherArgs: any[]): IStructuredError | IStructuredError[];
    panic(arg: any, ...otherArgs: any[]): void;
    end(): void;
    done(): void;
    total: number;
    span: Span;
}
export declare const createProgressReporter: ({ id, text, start, total, span, reporter, }: ICreateProgressReporterArguments) => IProgressReporter;
export {};
