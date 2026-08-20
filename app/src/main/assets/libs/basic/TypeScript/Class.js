//How to  create a class

class Student {

    firstName: string;
    lastName: string;
    score: number;

    constructor(firstName: string, lastName: string, score: number) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.score = score;
    }

    getFullNameAndScore(): string {
        return `${this.firstName} ${this.lastName} ${this.score}`;
    }
}

let student: Student = new Student("Tim", "Stone", 80);
let fullNameAndScore: string = student.getFullNameAndScore();
console.log(fullNameAndScore);